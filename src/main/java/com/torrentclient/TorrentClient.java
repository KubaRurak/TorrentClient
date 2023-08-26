package com.torrentclient;

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.torrentclient.exceptions.WrongMessageTypeException;
import com.torrentclient.exceptions.WrongPayloadLengthException;

public class TorrentClient implements PieceMessageCallback, ClientExceptionCallback {
	
    private ExecutorService connectionThreadPool;
    private PeriodicChecker periodicChecker;
    private SpeedLogger speedLogger;

    private static final int maxBlockSize = 16384;
    private int numberOfPieces;
	private int blocksPerPiece;
	
    private String path = "torrentfile/debian-12.1.0-mipsel-netinst.iso.torrent";
    private String storagePath = "C:" + File.separator + "torrentfile" + File.separator + "downloads" + File.separator + "backup";
    
    private Torrent torrent;
    private FileManager fileManager;

	private ConcurrentHashMap<Integer,PieceState> pieceStates;
    private Bitfield downloadedPiecesBitfield;
    private Queue<PieceState> pieceQueue;
    private Set<Integer> piecesBeingDownloaded;
    private final List<Client> activeClients = Collections.synchronizedList(new ArrayList<>());
    

	
    private static final Logger logger = LoggerFactory.getLogger(TorrentClient.class);

    public void start() {
        initialize();
        process();
        finalizeDownload();
        cleanup();
    }

    private void initialize() {
        torrent = Torrent.fromFile(path);
        setupConnectionThreadPool();
        fileManager = new FileManager(storagePath, torrent.getName());
        initializeDataStructures();
        speedLogger = new SpeedLogger(numberOfPieces, downloadedPiecesBitfield, torrent.getLength());
        speedLogger.start();
	    periodicChecker = new PeriodicChecker(activeClients, pieceQueue,
			downloadedPiecesBitfield, this::isDownloadComplete, blocksPerPiece);
	    periodicChecker.start();
    }

    private void process() {
        List<Peer> peerList = getPeerList();
        startDownloading(peerList);
    }

    private void cleanup() {
        logger.debug("Shutting down connections");
        disconnectActiveClients(); 
        connectionThreadPool.shutdown();
        logger.debug("After connection ThreadPoll shutdown");
        if (isDownloadComplete()) {
        	logger.info("Download succesfull, closing app");
        	System.exit(0); // need to fix this to not have to rely on that
        }
        try {
            connectionThreadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
            logger.debug("Terminating threads");
        } catch (InterruptedException e) {
            logger.debug("Download threads interrupted", e);
        }
        logger.debug("threads terminated");
        speedLogger.stop();
        periodicChecker.stop();
        
    }

    private void finalizeDownload() {
    	try {
    		if (!isDownloadComplete()) {
    			logger.debug("Download was not completed successfully");
    			return;
    		}
    		if (!fileManager.isFileMerged()) {
    			fileManager.mergeFiles(numberOfPieces, torrent.getLength());
    		}
    	} finally {
    		cleanup();
    	}
    }

    private void setupConnectionThreadPool() {
        int numThreads = 6; 
        connectionThreadPool = Executors.newFixedThreadPool(numThreads);
    }

    private List<Peer> getPeerList() {
        List<String> requestUrls = torrent.createRequestURLs();
        return Peer.fetchPeers(requestUrls);
    }
    
    private void startDownloading(List<Peer> peerList) {
    	Handshake handshake = new Handshake(torrent.getInfoHash(), torrent.getPeerIdBytes());
    	for (Peer peer : peerList) {
    		connectionThreadPool.submit(() -> {
    			if (isDownloadComplete()) return;
    			Client client = new Client(torrent, peer, handshake, this, this);
    			logger.debug("new client");
    			if (client.initializeConnection()) {
    				activeClients.add(client);
    				attemptDownloadPiece(client);
    				activeClients.remove(client);
    			}
    		});
    	}
    }

    private void attemptDownloadPiece(Client client) {
        try {
            setupDownload(client);
            processPieces(client);
        } catch (IOException e) {
            logger.debug("IOException during piece download", e);
        } catch (Exception e) {
            logger.debug("Error during piece download", e);
        }
    }

    private void setupDownload(Client client) throws IOException {
    	if (isDownloadComplete()) finalizeDownload();
        client.sendInterestedMessage();
        logger.debug("Sending interested message");
    }
    
    private void processPieces(Client client) throws InterruptedException, IOException {
    	while ((!pieceQueue.isEmpty() || !piecesBeingDownloaded.isEmpty() || !client.workQueue.isEmpty()) && client.isSocketOpen()) {
            if (!client.isChoked()) {
            	logger.debug("Inside loop: pieceQueue size: " + pieceQueue.size() + ", piecesBeingDownloaded size: " + piecesBeingDownloaded.size());
                if (client.workQueue.size() < blocksPerPiece) {
                    // If workQueue has less blocks than a typical piece, get a new piece and add its blocks
                    Optional<PieceState> optionalPieceState = chooseRandomPiece(client);
                    if (optionalPieceState.isPresent()) {
                        PieceState currentPieceState = optionalPieceState.get();
                        int pieceIndex = currentPieceState.getPieceIndex();
                        piecesBeingDownloaded.add(pieceIndex);
                        populateWorkQueueIfNeeded(client, pieceIndex);
                    }
                }
                sendBlockRequests(client);
            }
            handleIncomingMessages(client);
        }
    }
    
    
    private void populateWorkQueueIfNeeded(Client client, int pieceIndex) {
        if (!needsMoreBlocks(client)) {
        	logger.debug("Doesnt need more blocks, returning");
        	return; 
        }
        populateWorkQueue(client, pieceIndex, torrent.getPieceSize(pieceIndex));
    }
    
    void populateWorkQueue(Client client, int pieceIndex, int pieceSize) {
        int blocks = pieceSize / maxBlockSize;
        logger.debug("Number of blocks {} for piece index {}", blocks, pieceIndex);
        for (int i = 0; i < blocks; i++) {
            BlockRequest request = new BlockRequest(pieceIndex, i * maxBlockSize, maxBlockSize);
            client.workQueue.offer(request);
        }
        // Handling the last block, which might be smaller than maxBlockSize
        if (pieceSize % maxBlockSize != 0) {
            int begin = blocks * maxBlockSize;
            BlockRequest request = new BlockRequest(pieceIndex, begin, pieceSize - begin);
            client.workQueue.offer(request);
        }
    }

    private void sendBlockRequests(Client client) throws IOException {
        while (client.currentOutstandingRequests < Client.MAX_OUTSTANDING_REQUESTS && !client.workQueue.isEmpty()) {
            BlockRequest request = client.workQueue.poll();
            client.sendRequestMessage(request);
            client.outstandingRequests.add(request);
            client.currentOutstandingRequests++;
        }
        logger.debug("Finished sending block requests. Total outstanding requests: " + client.currentOutstandingRequests);
    }


    private void handleIncomingMessages(Client client)  {
        try {
            Message message = client.receiveAndParseMessage();
            client.handleMessage(message);
        } catch (SocketTimeoutException e) {
            logger.debug("Client timed out");
            client.closeConnection();
        } catch (IOException e) {
            logger.debug("An error occurred while handling incoming messages");
            client.closeConnection();
        }
    }

    @Override
    public void onPieceMessageReceived(Message message, Client client) {
        try {
            int pieceIndex = extractPieceIndex(message);
            ByteBuffer buf = retrieveOrCreateBuffer(client, pieceIndex);
            PieceMessageInfo info = Message.parsePieceMessage(pieceIndex, buf, message); //buffer updated here
            int blockIndex = info.getBegin() / maxBlockSize;
            PieceState pieceState = getPieceStateByIndex(pieceIndex);
            pieceState.markBlockReceived(blockIndex);
            client.currentOutstandingRequests--;
            BlockRequest blockRequestToRemove = new BlockRequest(pieceIndex, info.getBegin(), maxBlockSize); // Adjusted here
            client.outstandingRequests.remove(blockRequestToRemove);
            
            if (pieceState != null) {
                pieceState.markBlockReceived(blockIndex);
                logger.debug("Piece {} Block {} received. Total blocks received for this piece: {}", pieceIndex, blockIndex, pieceState.getBlocksReceived());
            }
            if (!buf.hasRemaining()) {
            	logger.debug("Buffer for Piece {} is full",pieceIndex);
                handleFullPiece(pieceIndex, buf, client);
            }
        } catch (Exception e) {
            logger.debug("Caught exception on pieceMessage");;
        }
    }

    private PieceState getPieceStateByIndex(int pieceIndex) {
    	return pieceStates.get(pieceIndex);
	}
	private int extractPieceIndex(Message message) {
        return ByteBuffer.wrap(message.getPayload(), 0, 4).getInt();
    }
	

    private ByteBuffer retrieveOrCreateBuffer(Client client, int pieceIndex) {
        return client.pieceBuffers.computeIfAbsent(pieceIndex, k -> {
            int pieceSize = torrent.getPieceSize(pieceIndex);
            return ByteBuffer.allocate(pieceSize);
        });
    }

    private void handleFullPiece(int pieceIndex, ByteBuffer buf, Client client) {
        byte[] pieceData = buf.array();
        if (verifyPieceIntegrity(pieceData,pieceIndex)) {
        	logger.debug("piece is verified!");
            fileManager.savePieceToDisk(pieceIndex, pieceData);
            speedLogger.addBytesDownloaded(torrent.getPieceLength());
            downloadedPiecesBitfield.setPiece(pieceIndex);
            client.pieceBuffers.remove(pieceIndex);
            piecesBeingDownloaded.remove(pieceIndex);
            if (isDownloadComplete()) {
            	finalizeDownload();
            }
        } else {
            handleCorruptPiece(buf, pieceIndex);
        }
    }

    private void handleCorruptPiece(ByteBuffer buf, int pieceIndex) {
        buf.clear();
        pieceQueue.offer(new PieceState(blocksPerPiece, pieceIndex));
        piecesBeingDownloaded.remove(pieceIndex);
    }


    private boolean verifyPieceIntegrity(byte[] pieceData, int pieceIndex) {
    	byte[] expectedHash = torrent.getPieceHash(pieceIndex);
        byte[] calculatedHash = computeSHA1(pieceData);
        return Arrays.equals(expectedHash, calculatedHash);
    }
    
    
    private boolean shouldDownloadPiece(Client client, int pieceIndex) {
        return client.getBitfield().hasPiece(pieceIndex);
//        return (!piecesBeingDownloaded.contains(pieceIndex)) && client.getBitfieldObject().hasPiece(pieceIndex);
    }
    
    private boolean needsMoreBlocks(Client client) {
        return client.workQueue.size() < blocksPerPiece;
    }
    
    private Optional<PieceState> chooseRandomPiece(Client client) {
    	logger.debug("Choosing a random piece");
        int piecesChecked = 0;
        int queueSize = pieceQueue.size();
        while (piecesChecked < queueSize && !pieceQueue.isEmpty()) {
            PieceState piece = pieceQueue.poll();
            int pieceIndex = piece.getPieceIndex();
            logger.debug("PiecesBeingDownloaded contains pieceIndex {} : {}",pieceIndex, piecesBeingDownloaded.contains(pieceIndex));
            logger.debug("Does client have that piece? : " + client.getBitfield().hasPiece(pieceIndex));
            if (shouldDownloadPiece(client, pieceIndex)) {
            	logger.debug("Chosen piece index: " + pieceIndex);
                return Optional.of(piece);
            } else {
            	logger.debug("returning Piece index " + pieceIndex);
                pieceQueue.offer(piece);
                logger.debug("current PieceQueue size " + pieceQueue.size());
            }
            piecesChecked++;
        }
        return Optional.empty();
    }
	
	private synchronized boolean isDownloadComplete() {
	    return downloadedPiecesBitfield.cardinality() == numberOfPieces;
	}
	
    private void initializeDataStructures() {
    	initializeBitfield();
    	initializePieceQueue();
    	initializePiecesBeingDownloadedSet();
    	initializePieceStatesMap();
    }
    private void initializeBitfield() {
        this.numberOfPieces = torrent.getPieces().length / 20;
        byte[] initialBitfieldData = new byte[(numberOfPieces + 7) / 8];
        downloadedPiecesBitfield = new Bitfield(initialBitfieldData);
        for (int i = 0; i < numberOfPieces; i++) {
            if (fileManager.isPieceDownloaded(i)) {
                downloadedPiecesBitfield.setPiece(i);
            }
        }
    }
    
    private void initializePieceQueue() {
    	pieceQueue = new ConcurrentLinkedQueue<>();
        this.blocksPerPiece = (int) torrent.getPieceLength() / maxBlockSize;
        List<PieceState> pieceList = new ArrayList<>();
        for (int i = 0; i < numberOfPieces; i++) {
            if (!downloadedPiecesBitfield.hasPiece(i)) {
                pieceList.add(new PieceState(blocksPerPiece, i));
            }
        }
        Collections.shuffle(pieceList); // Randomize the order
        pieceQueue.addAll(pieceList); // Add all the shuffled pieces to the queue
    }
    
    private void initializePiecesBeingDownloadedSet() {
    	piecesBeingDownloaded = ConcurrentHashMap.newKeySet();
    }
    
    private void initializePieceStatesMap() {
        pieceStates = new ConcurrentHashMap<>();
        for (int i = 0; i < numberOfPieces; i++) {
            PieceState pieceState = new PieceState(blocksPerPiece, i);
            if (downloadedPiecesBitfield.hasPiece(i)) {
                pieceState.markComplete();
            }
            pieceStates.put(i, pieceState);
        }
    }
    
    private byte[] computeSHA1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void onException(Client client, Exception e) {
        if (e instanceof SocketTimeoutException) {
            logger.debug("Socket Timeout Exception");
            onConnectionClosed(client);
        } else if (e instanceof IOException) {
            logger.debug("IO exception", e);
            onConnectionClosed(client);
            client.closeConnection();
        } else if (e instanceof WrongMessageTypeException) {
            logger.debug("Received an invalid message type", e);
        } else if (e instanceof WrongPayloadLengthException) {
            logger.debug("Received a message with incorrect payload length", e);
        }
    }
    
    public void onConnectionClosed(Client client) {
        logger.warn("Connection closed for client " + client);
        Set<Integer> distinctPieceIndexes = new HashSet<>();
        while (!client.workQueue.isEmpty()) {
            BlockRequest request = client.workQueue.poll();
            distinctPieceIndexes.add(request.getPieceIndex());
        }
        for (Integer pieceIndex : distinctPieceIndexes) {
            pieceQueue.offer(new PieceState(blocksPerPiece, pieceIndex));
            piecesBeingDownloaded.remove(pieceIndex);
        }
    }
    
    private void disconnectActiveClients() {
        logger.debug("Disconnecting all active clients...");
        synchronized (activeClients) {
            for (Client client : activeClients) {
                try {
                    client.closeConnection();
                } catch (Exception e) {
                    logger.debug("Error disconnecting client: " + client.toString(), e);
                }
            }
            activeClients.clear();
        }
        logger.debug("All active clients disconnected.");
    }
}
