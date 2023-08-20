package com.torrentclient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import unet.bencode.variables.BencodeObject;

public class TorrentClient implements PieceMessageCallback {
	
//	static String response = "d8:intervali900e5:peersld2:ip13:185.203.56.244:porti55266eed2:ip14:209.141.56.2364:porti6985eed2:ip13:45.158.186.114:porti54413eed2:ip15:134.249.136.1124:porti29905eed2:ip12:133.201.88.04:porti6882eed2:ip13:82.64.248.2444:porti24007eed2:ip13:82.196.124.874:porti16881eed2:ip12:185.203.56.64:porti59279eed2:ip12:61.205.220.44:porti59153eed2:ip13:130.61.89.1554:porti6881eed2:ip15:185.236.203.1244:porti43421eed2:ip15:149.102.137.2304:porti21250eed2:ip15:169.150.201.1634:porti48000eed2:ip14:31.192.237.1204:porti41722eed2:ip13:45.41.206.1214:porti53391eed2:ip15:142.113.144.1824:porti51765eed2:ip14:176.114.248.344:porti51453eed2:ip14:176.226.164.724:porti5555eed2:ip14:188.156.237.204:porti49164eed2:ip15:185.194.143.2014:porti26111eed2:ip13:62.153.208.984:porti3652eed2:ip13:185.149.90.214:porti51055eed2:ip15:189.245.179.2534:porti51765eed2:ip13:188.126.89.804:porti27049eed2:ip13:185.253.96.584:porti60246eed2:ip15:146.158.111.1894:porti51413eed2:ip13:93.221.180.904:porti6881eed2:ip14:199.168.73.1264:porti1330eed2:ip12:73.183.68.194:porti51413eed2:ip13:45.13.105.1354:porti51413eed2:ip12:212.7.200.654:porti12665eed2:ip13:82.65.147.1504:porti51414eed2:ip14:45.134.140.1404:porti6881eed2:ip13:51.159.104.654:porti7629eed2:ip14:68.134.157.2134:porti51413eed2:ip13:41.225.80.1504:porti1821eed2:ip14:120.229.36.2134:porti56339eed2:ip13:92.43.185.1114:porti61598eed2:ip12:31.22.89.1114:porti6897eed2:ip12:51.68.81.2274:porti6881eed2:ip12:185.148.1.834:porti52528eed2:ip12:189.6.26.1234:porti51413eed2:ip12:151.95.0.2464:porti51413eed2:ip13:87.227.189.894:porti55000eed2:ip15:185.213.154.1794:porti40455eed2:ip12:108.26.1.1554:porti6942eed2:ip12:62.12.77.1524:porti51413eed2:ip13:95.24.217.1984:porti35348eed2:ip12:85.29.89.1274:porti6881eed2:ip13:81.200.30.1344:porti13858eeee";
    private List<Socket> activeSockets = new ArrayList<>();
    private Queue<PieceState> pieceQueue;
    private ExecutorService connectionThreadPool;
    private final int maxBlockSize = 16384;
    private int downloaded;
    private int piecesLeft;
    private Torrent torrent;
    private Map<Integer, PieceState> pieceStates = new HashMap<>();    
    private final Map<Integer, ByteBuffer> currentPieceBuffers = new HashMap<>();
	private String storagePath = "torrentfile/";
	private int blocksPerPiece;
	
    private static final Logger logger = LoggerFactory.getLogger(TorrentClient.class);


	
	public void start() {
        torrent = initializeTorrent();
        setupConnectionThreadPool();
        initializePieceQueue();

        // Start downloading pieces
        List<Peer> peerList = getPeerList();
        startDownloading(peerList);

        // Clean up resources
        connectionThreadPool.shutdown();
        disconnectAllSockets();
	}
    private Torrent initializeTorrent() {
        String filePath = "torrentfile/debian-12.1.0-mipsel-netinst.iso.torrent";
//        String filePath = "torrentfile/TAILS.torrent";
//        String filePath = "torrentfile/debian-edu-12.1.0-amd64-netinst.iso.torrent";
        try {
            return createTorrentObjectFromFile(filePath);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException("Failed to initialize Torrent.");
        }
    }

    private void setupConnectionThreadPool() {
        int numThreads = 7; // Adjust the number of threads as needed
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
        final int MAX_RETRIES = 2;
        int retryCount = 0;
        try {
            setupDownload(client);
            processPieces(client, retryCount, MAX_RETRIES);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupDownload(Client client) throws IOException {
        try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
        client.sendInterestedMessage();
        logger.info("Sending interested message");
    }

    private void processPieces(Client client, int retryCount, final int MAX_RETRIES) throws InterruptedException, IOException {
        logger.info("Entered processPiece method");
        while (!pieceQueue.isEmpty() && client.isSocketOpen() &&  retryCount < MAX_RETRIES) {
            if (!client.isChoked()) {
                PieceState currentPieceState = pieceQueue.peek();
                int pieceIndex = currentPieceState.getPieceIndex();
                logger.info("Grabbed pieceIndex: " + pieceIndex + " from queue");
                logger.info("Checking if client Bitfield has piece : " + client.getBitfieldObject().hasPiece(pieceIndex));

                if (client.getBitfieldObject().hasPiece(pieceIndex) && client.getOutstandingRequests().size()==0) {
                	currentPieceState = pieceQueue.poll();
                	pieceIndex = currentPieceState.getPieceIndex();
                    int pieceSize = getPieceSize(pieceIndex);
                    downloadPiece(client, pieceIndex, pieceSize);
                } else {
                    retryCount++; // assuming retryCount is to track the number of failed attempts
                    Thread.sleep(1000); // introduce a delay before attempting the next piece
                }
            }
            handleIncomingMessages(client, retryCount, MAX_RETRIES);
            Thread.sleep(1000);
        }

//        if (retryCount >= MAX_RETRIES) {
//            logger.info("Max retries reached for client. Closing connection.");
//            client.closeConnection();
//        }
        
    }

    private void handleIncomingMessages(Client client, int retryCount, final int MAX_RETRIES) throws InterruptedException, IOException {
        try {
            Message message = client.receiveAndParseMessage();
            client.handleMessage(message);
            retryCount = 0; 
        } catch (SocketTimeoutException e) {
            logger.warn("Timed out in handleIncomingMessage");
            logger.warn("Trying to resend request");
            resendOutstandingRequests(client);
            retryCount++;
        } catch (Exception e) {
            logger.error("An error occurred while handling incoming messages", e);
//            client.setCurrentOutstandingRequests(0);
            client.closeConnection();

		}
    }

    private void downloadPiece(Client client, int pieceIndex, int pieceSize) {
        if (!client.isSocketOpen()) return;

        int blockSize = maxBlockSize;
        int numOfBlocksInPiece = getNumberOfBlocksInPiece(pieceSize, blockSize);
        int blockIndex = 0;
        while (blockIndex < numOfBlocksInPiece && client.isSocketOpen() && client.canSendMoreRequests()) {
            int begin = blockIndex * blockSize;
            int blockLength = Math.min(blockSize, pieceSize - begin);

            sendBlockRequest(client, pieceIndex, begin, blockLength);
            client.incrementOutstandingRequests();
            blockIndex++;
        }
        logger.info("End of requests, should go back to handling message");

        // Then, let the attemptDownloadPiece method handle received messages.
    }

    private void sendBlockRequest(Client client, int pieceIndex, int begin, int blockLength) {
        if (!client.isSocketOpen()) return;
        try {
            logger.info("Sending request message for pieceIndex: {} begin: {} and blocklength: {}", pieceIndex, begin, blockLength);
            client.sendRequestMessage(pieceIndex, begin, blockLength);
            BlockRequest request = new BlockRequest(pieceIndex, begin, blockLength);
            client.getOutstandingRequests().add(request);
        } catch (IOException e) {
            logger.error("Failed to send block request for pieceIndex: {} begin: {} blocklength: {}", pieceIndex, begin, blockLength, e);
        }
    }
    
    @Override
    public void onPieceMessageReceived(Message message, Client client) {
        try {
            int pieceIndex = Message.getPieceIndexFromMessage(message);
            client.decrementOutstandingRequests();
            
            // Calculate block index
            int beginOffset = Message.getBeginOffsetFromMessage(message);
            int blockIndex = beginOffset / maxBlockSize;
            
            // Update the PieceState
            PieceState currentPieceState = findPieceStateByIndex(pieceIndex); // This should be a helper method you provide
            currentPieceState.markBlockReceived(blockIndex);
            
            // Get or create the buffer for this piece
            ByteBuffer pieceBuffer = currentPieceBuffers
                .computeIfAbsent(pieceIndex, k -> ByteBuffer.allocate((int) torrent.getPieceLength()));
            
            // Parse the message
            Message.parsePieceMessage(pieceIndex, pieceBuffer, message);
            
            // Check if the piece is complete
            if (currentPieceState.isPieceComplete()) {
                handleCompletedPiece(pieceIndex, pieceBuffer.array());
                currentPieceBuffers.remove(pieceIndex);
            }

        } catch (Exception e) {
            logger.error("Error processing piece message", e);
        }
    }
    
    private void resendOutstandingRequests(Client client) {
        if (!client.isSocketOpen()) return;

        for (BlockRequest request : client.getOutstandingRequests()) {
            sendBlockRequest(client, request.pieceIndex, request.begin, request.blockLength);
        }
    }

	
    
    private PieceState findPieceStateByIndex(int pieceIndex) {
    	return pieceStates.get(pieceIndex);
	}

	private void handleCompletedPiece(int pieceIndex, byte[] pieceData) {
        if (verifyPieceIntegrity(pieceData, torrent.getPieceHash(pieceIndex))) {
            savePieceToDisk(pieceIndex, pieceData, torrent.getName());
            logger.info("Succesfully completed piece nr: " + pieceIndex);
        } else {
        	pieceQueue.add(new PieceState(blocksPerPiece, pieceIndex));
        }
    }

    private boolean verifyPieceIntegrity(byte[] pieceData, byte[] expectedHash) {
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
        
        try (FileOutputStream fos = new FileOutputStream(fragmentFileName)) {
            fos.write(pieceData);
        } catch (IOException e) {
            e.printStackTrace();
            // Handle this exception, perhaps by logging or notifying the user.
        }
    }
	
    private void initializePieceQueue() {
        int numberOfPieces = torrent.getPieces().length / 20;
        downloaded = 0;
        piecesLeft = numberOfPieces;
        pieceQueue = new ArrayDeque<>();
        
        
        this.blocksPerPiece = (int) torrent.getPieceLength() / maxBlockSize; // assuming maxBlockSize is defined elsewhere
        
        for (int i=0; i<100; i++) {  // you seem to want only the first 5 pieces for testing?
            pieceQueue.add(new PieceState(blocksPerPiece, i));
        }
        logger.info("Initialized pieceQueue with " + numberOfPieces + " number of pieces");
    }

  
    

	private int getPieceSize(int pieceIndex) {
        long numPieces = torrent.getPieces().length;
        if (pieceIndex == numPieces - 1) {
        	int remainingData = (int) (torrent.getLength()%torrent.getPieceLength());
        	return (remainingData > 0) ? remainingData : (int) torrent.getPieceLength();
        } else {
        	return (int) torrent.getPieceLength();
        }
    }
    
    private int getNumberOfBlocksInPiece(int pieceSize, int blockSize) {
        return (int) Math.ceil(pieceSize/blockSize);
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
	
    private void disconnectAllSockets() {
        for (Socket socket : activeSockets) {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    @PreDestroy
    public void onApplicationExit() {
        disconnectAllSockets();
    }

}
