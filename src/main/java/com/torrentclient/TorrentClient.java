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
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.torrentclient.exceptions.WrongMessageTypeException;
import com.torrentclient.exceptions.WrongPayloadLengthException;

public class TorrentClient implements PieceMessageCallback {
	
    private ExecutorService connectionThreadPool;
    private static final int maxBlockSize = 16384;
    private int numberOfPieces;
    private String path = "torrentfile/debian-12.1.0-mipsel-netinst.iso.torrent";
    private SpeedLogger speedLogger;
    private Torrent torrent;
    private FileManager fileManager;
    private String storagePath = "C:" + File.separator + "torrentfile" + File.separator + "downloads";
	private int blocksPerPiece;
	private ConcurrentHashMap<Integer,PieceState> pieceStates;
    private Bitfield downloadedPiecesBitfield;
    private Queue<PieceState> pieceQueue;
    private Set<Integer> piecesBeingDownloaded;
	
    private static final Logger logger = LoggerFactory.getLogger(TorrentClient.class);


	
    public void start() {
        initialize();
        process();
        cleanup();
    }

    private void initialize() {
        torrent = Torrent.fromFile(path);
        setupConnectionThreadPool();
        initializeDataStructures();
        speedLogger = new SpeedLogger(numberOfPieces, downloadedPiecesBitfield);
        speedLogger.start();
        fileManager = new FileManager(storagePath, torrent.getName());
    }

    private void process() {
        List<Peer> peerList = getPeerList();
        startDownloading(peerList);
    }

    private void cleanup() {
        connectionThreadPool.shutdown();
        try {
            connectionThreadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            logger.error("Download threads interrupted", e);
        }
        
        finalizeDownload();
    }

    private void finalizeDownload() {
        if (isDownloadComplete()) {
            try {
                fileManager.mergeFiles(numberOfPieces);
            } catch (IOException e) {
                logger.error("Failed to merge files after download", e);
            }
        } else {
            logger.error("Download was not completed successfully");
        }
    }


    private void setupConnectionThreadPool() {
        int numThreads = 1; // Adjust the number of threads as needed
        connectionThreadPool = Executors.newFixedThreadPool(numThreads);
    }

    private List<Peer> getPeerList() {
        String requestUrl = torrent.createRequestURL();
        return Peer.fetchPeers(requestUrl);
    }

    private void startDownloading(List<Peer> peerList) {
        Handshake handshake = new Handshake(torrent.getInfoHash(), torrent.getPeerIdBytes());
        for (Peer peer : peerList) {
            connectionThreadPool.submit(() -> {
                Client client = new Client(torrent, peer, handshake, this);
                if (client.initializeConnection()) {
                    attemptDownloadPiece(client);
                }
            });
        }
    }

    private void attemptDownloadPiece(Client client) {
        try {
            setupDownload(client);
            processPieces(client);
        } catch (IOException e) {
            logger.error("IOException during piece download", e);
        } catch (Exception e) {
            logger.error("Error during piece download", e);
        }
    }

    private void setupDownload(Client client) throws IOException {
        client.sendInterestedMessage();
        logger.info("Sending interested message");
    }

    private void processPieces(Client client) throws InterruptedException, IOException {
        while (!pieceQueue.isEmpty() && client.isSocketOpen()) {
            if (!client.isChoked()) {
                if (client.workQueue.size() < blocksPerPiece) {
                    // If workQueue has less blocks than a typical piece, get a new piece and add its blocks
                    PieceState currentPieceState = chooseRandomPiece(client);
                    if (currentPieceState == null) continue; // No piece was chosen because of bitfield constraints
                    
                    int pieceIndex = currentPieceState.getPieceIndex();
                    logger.info("Grabbed pieceIndex: " + pieceIndex + " from queue");
                    
                    // Populate the workQueue for this piece
                    populateWorkQueue(client, pieceIndex, torrent.getPieceSize(pieceIndex));
                }
                
                // Send block requests, whether they are from a newly populated workQueue or from an existing one
                sendBlockRequests(client);
            }
            
            handleIncomingMessages(client);
        }
        logger.info("Thread for client " + client + " is finishing execution.");

    }
    
    void populateWorkQueue(Client client, int pieceIndex, int pieceSize) {
        int blocks = pieceSize / maxBlockSize;
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
    }


    private void handleIncomingMessages(Client client)  {
        try {
            Message message = client.receiveAndParseMessage();
            client.handleMessage(message);
        } catch (SocketTimeoutException e) {
            logger.error("Timed out in handleIncomingMessage");
        } catch (IOException e) {
            logger.error("An error occurred while handling incoming messages", e);
            client.closeConnection();
        } catch (WrongMessageTypeException e) {
            logger.error("Received an invalid message type", e);
            // client.closeConnection();
        } catch (WrongPayloadLengthException e) {
            logger.error("Received a message with incorrect payload length", e);
            // client.closeConnection();
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
            if (pieceState != null) {
                pieceState.markBlockReceived(blockIndex);
                logger.info("Piece {} Block {} received. Total blocks received for this piece: {}", pieceIndex, blockIndex, pieceState.getBlocksReceived());
            }
            if (!buf.hasRemaining()) {
            	logger.info("Buffer for Piece {} is full",pieceIndex);
                handleFullPiece(pieceIndex, buf, client);
            }

            client.currentOutstandingRequests--;

        } catch (Exception e) {
            e.printStackTrace();
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
            int pieceSize = torrent.getPieceSize(pieceIndex); // Assuming you have a method that provides piece size
            return ByteBuffer.allocate(pieceSize);
        });
    }

    private void handleFullPiece(int pieceIndex, ByteBuffer buf, Client client) {
        byte[] pieceData = buf.array();
        if (verifyPieceIntegrity(pieceData,pieceIndex)) {
        	logger.info("piece is verified!");
            fileManager.savePieceToDisk(pieceIndex, pieceData);
            speedLogger.addBytesDownloaded(torrent.getPieceLength());
            downloadedPiecesBitfield.setPiece(pieceIndex);
            client.pieceBuffers.remove(pieceIndex);
            piecesBeingDownloaded.remove(pieceIndex);
        } else {
            handleCorruptPiece(buf, pieceIndex);
        }
    }

    private void handleCorruptPiece(ByteBuffer buf, int pieceIndex) {
        buf.clear();
        pieceQueue.offer(new PieceState(blocksPerPiece, pieceIndex)); // Ensure you create a new PieceState appropriately
    }


    private boolean verifyPieceIntegrity(byte[] pieceData, int pieceIndex) {
    	byte[] expectedHash = torrent.getPieceHash(pieceIndex);
        byte[] calculatedHash = computeSHA1(pieceData);
        return Arrays.equals(expectedHash, calculatedHash);
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

	
    private void initializeDataStructures() {
    	initializeBitfield();
    	initializePieceQueue();
    	initializePiecesBeingDownloadedSet();
    	initializePieceStatesMap();
    }
    private void initializeBitfield() {
        this.numberOfPieces = torrent.getPieces().length / 20;
        byte[] initialBitfieldData = new byte[(numberOfPieces + 7) / 8];  // Round up to the nearest byte
        downloadedPiecesBitfield = new Bitfield(initialBitfieldData);
        for (int i = 0; i < numberOfPieces; i++) {
            if (isPieceDownloaded(i)) {
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
                pieceState.markComplete(); // Set as downloaded
            }
            pieceStates.put(i, pieceState);
        }
    }
    
    private boolean isPieceDownloaded(int pieceIndex) {
        String pieceFileName = storagePath + File.separator + torrent.getName() + ".piece." + pieceIndex;
        File pieceFile = new File(pieceFileName);
        return pieceFile.exists();
    }
    
    PieceState chooseRandomPiece(Client client) {
        int piecesChecked = 0;
        int queueSize = pieceQueue.size();

        while (piecesChecked < queueSize && !pieceQueue.isEmpty()) {
            PieceState piece = pieceQueue.poll(); // Remove and return the head
            int pieceIndex = piece.getPieceIndex();
            
            piecesChecked++;

            if (!piecesBeingDownloaded.contains(pieceIndex) && client.getBitfieldObject().hasPiece(pieceIndex)) {
                piecesBeingDownloaded.add(pieceIndex);
                logger.info("Polling piece nr {}", pieceIndex);
                return piece;
            } else {
                pieceQueue.offer(piece);
            }
        }
        return null;
    }
	
	private boolean isDownloadComplete() {
	    return downloadedPiecesBitfield.cardinality() == numberOfPieces;
	}
	
}
