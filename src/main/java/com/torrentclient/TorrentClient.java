package com.torrentclient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import unet.bencode.variables.BencodeObject;

public class TorrentClient implements PieceMessageCallback {
	
    private ExecutorService connectionThreadPool;
    private final int maxBlockSize = 16384;
    private int numberOfPieces;
    private long bytesDownloaded = 0;
    private long previousDownloaded = 0;

    private long startTime;
    private ScheduledExecutorService speedLogger;
    private Torrent torrent;
    private String storagePath = "C:" + File.separator + "torrentfile" + File.separator + "downloads";
	private int blocksPerPiece;
	private ConcurrentHashMap<Integer,PieceState> pieceStates;
    private Bitfield downloadedPiecesBitfield;
    private Queue<PieceState> pieceQueue;
    private Set<Integer> piecesBeingDownloaded;
	
    private static final Logger logger = LoggerFactory.getLogger(TorrentClient.class);


	
	public void start() {
        torrent = initializeTorrent();
        setupConnectionThreadPool();
        initiaLizeDataStructures();

        // Start downloading pieces
        List<Peer> peerList = getPeerList();
        startSpeedLogger();
        startDownloading(peerList);

        // Clean up resources
        connectionThreadPool.shutdown();
        try {
            connectionThreadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            logger.error("Download threads interrupted", e);
        }

        // Check if the download is complete
        if (isDownloadComplete()) {
            try {
				mergeFiles();
			} catch (IOException e) {
				e.printStackTrace();
			}
        } else {
            logger.error("Download was not completed successfully");
        }
	}
    private Torrent initializeTorrent() {
        String filePath = "torrentfile/debian-12.1.0-mipsel-netinst.iso.torrent";

        try {
            return createTorrentObjectFromFile(filePath);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize Torrent.");
        }
    }
    
    private void startSpeedLogger() {
        this.startTime = System.currentTimeMillis();
        this.speedLogger = Executors.newSingleThreadScheduledExecutor();

        // Schedule to log download speed every second
        this.speedLogger.scheduleAtFixedRate(() -> {
            logDownloadStatus();
        }, 1, 10, TimeUnit.SECONDS);
    }
    
    private void logDownloadStatus() {
    	logDownloadSpeed();
    	logPiecesProgress();
    }
    
    private void logDownloadSpeed() {
        long currentTime = System.currentTimeMillis();
        long timeElapsed = currentTime - startTime;  // time in milliseconds

        long bytesDownloadedInterval = bytesDownloaded - previousDownloaded;  // bytes downloaded during this interval
        double speed = bytesDownloadedInterval / (timeElapsed / 1000.0);  // bytes per second
        double speedInKBps = speed / 1024;  // Convert to KB per second

        logger.info("Download speed: " + String.format("%.2f", speedInKBps) + " KB/s");

        // Update for the next calculation
        this.previousDownloaded = bytesDownloaded;
        this.startTime = currentTime;
    }
    
    private void logPiecesProgress() {
        int totalPieces = numberOfPieces; // Assuming you have this value set already
        int downloaded = downloadedPiecesBitfield.cardinality();
        int remaining = totalPieces - downloaded;
        double progress = ((double) downloaded / totalPieces) * 100;

        logger.info(String.format("Pieces downloaded: %d/%d. Remaining: %d. Progress: %.2f%%", 
            downloaded, totalPieces, remaining, progress));
    }
    
    public synchronized void addBytesDownloaded(long bytes) {
        this.bytesDownloaded += bytes;
    }

    private void setupConnectionThreadPool() {
        int numThreads = 1; // Adjust the number of threads as needed
        connectionThreadPool = Executors.newFixedThreadPool(numThreads);
    }

    private List<Peer> getPeerList() {
        String requestUrl = torrent.createRequestURL();
        String responseWithPeerList = requestResponseWithPeerList(requestUrl);
        return makePeerListFromResponse(responseWithPeerList);
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
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
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
                    populateWorkQueue(client, pieceIndex, getPieceSize(pieceIndex));
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


    private void handleIncomingMessages(Client client) throws InterruptedException, IOException {
        try {
            Message message = client.receiveAndParseMessage();
            client.handleMessage(message);
        } catch (SocketTimeoutException e) {
            logger.info("Timed out in handleIncomingMessage");
        } catch (Exception e) {
            logger.error("An error occurred while handling incoming messages", e);
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
            int pieceSize = getPieceSize(pieceIndex); // Assuming you have a method that provides piece size
            return ByteBuffer.allocate(pieceSize);
        });
    }

    private void handleFullPiece(int pieceIndex, ByteBuffer buf, Client client) {
        byte[] pieceData = buf.array();
        if (verifyPieceIntegrity(pieceData,pieceIndex)) {
        	logger.info("piece is verified!");
            savePieceToDisk(pieceIndex, pieceData, torrent.getName());
            addBytesDownloaded(torrent.getPieceLength());
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

    private void savePieceToDisk(int pieceIndex, byte[] pieceData, String torrentName) {
        String fragmentFileName = storagePath + File.separator + torrentName + ".piece." + pieceIndex;
        logger.info("Attempting to save Piece {} to disk with name {}",pieceIndex,fragmentFileName);
        logger.info("Saving piece {}",pieceIndex);        
        // Ensure directories exist
        File file = new File(fragmentFileName);
        File parentDir = file.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }
        
        try (FileOutputStream fos = new FileOutputStream(fragmentFileName)) {
            fos.write(pieceData);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
	
    private void initiaLizeDataStructures() {
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
                pieceQueue.offer(piece); // Put the piece back at the end of the queue
            }
        }
        return null; // All pieces are being downloaded, the client doesn't have them, or the queue is empty
    }

  
    

	int getPieceSize(int pieceIndex) {
        long numPieces = torrent.getPieces().length;
        if (pieceIndex == numPieces - 1) {
        	int remainingData = (int) (torrent.getLength()%torrent.getPieceLength());
        	return (remainingData > 0) ? remainingData : (int) torrent.getPieceLength();
        } else {
        	return (int) torrent.getPieceLength();
        }
    }
    
	
	private List<Peer> makePeerListFromResponse(String responseWithPeerList) {
	    BencodeObject bencodeResponse = new BencodeObject(responseWithPeerList.getBytes());
	    return Peer.makePeerList(bencodeResponse);
	}
	
	private Torrent createTorrentObjectFromFile(String filePath) throws IOException{
		byte[] torrentData = Files.readAllBytes(Paths.get(filePath));
	    BencodeObject bencodeTorrentData = new BencodeObject(torrentData);
	    return new Torrent(bencodeTorrentData);
	}
	
	private String requestResponseWithPeerList(String requestUrl) {
	    HttpURLConnection connection = null;
	    StringBuilder response = new StringBuilder();

	    try {
	        connection = (HttpURLConnection) new URL(requestUrl).openConnection();
	        connection.setRequestMethod("GET");

	        int responseCode = connection.getResponseCode();
	        if (responseCode == HttpURLConnection.HTTP_OK) {
	            response = readResponse(connection);
	        } else {
	            logger.info("Request failed. Response Code: " + responseCode);
	        }
	    } catch (IOException e) {
	        e.printStackTrace();
	    } finally {
	        if (connection != null) {
	            connection.disconnect();
	        }
	    }

	    return response.toString();
	}

	private StringBuilder readResponse(HttpURLConnection connection) throws IOException {
	    StringBuilder response = new StringBuilder();
	    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
	        String line;
	        while ((line = reader.readLine()) != null) {
	            response.append(line);
	        }
	        logger.info("Response:");
	        logger.info(response.toString());
	    }
	    return response;
	}
	
	private boolean isDownloadComplete() {
	    return downloadedPiecesBitfield.cardinality() == numberOfPieces;
	}
	
	private void mergeFiles() throws IOException {
	    String outputFile = torrent.getName();
	    
	    // Merge process
	    try (FileOutputStream fos = new FileOutputStream(outputFile)) {
	        for (int i = 0; i < numberOfPieces; i++) {
	            File pieceFile = new File(storagePath + File.separator + "piece." + i);
	            Files.copy(pieceFile.toPath(), fos);
	        }
	    }

	    // If merging is successful, delete the pieces in a second iteration
	    for (int i = 0; i < numberOfPieces; i++) {
	        File pieceFile = new File(storagePath + File.separator + "piece." + i);
	        Files.deleteIfExists(pieceFile.toPath());
	    }
	}
	
	
}
