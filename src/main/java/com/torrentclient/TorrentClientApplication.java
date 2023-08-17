package com.torrentclient;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import jakarta.annotation.PreDestroy;
import unet.bencode.variables.BencodeObject;

@SpringBootApplication
public class TorrentClientApplication implements CommandLineRunner {
	
//	static String response = "d8:intervali900e5:peersld2:ip13:185.203.56.244:porti55266eed2:ip14:209.141.56.2364:porti6985eed2:ip13:45.158.186.114:porti54413eed2:ip15:134.249.136.1124:porti29905eed2:ip12:133.201.88.04:porti6882eed2:ip13:82.64.248.2444:porti24007eed2:ip13:82.196.124.874:porti16881eed2:ip12:185.203.56.64:porti59279eed2:ip12:61.205.220.44:porti59153eed2:ip13:130.61.89.1554:porti6881eed2:ip15:185.236.203.1244:porti43421eed2:ip15:149.102.137.2304:porti21250eed2:ip15:169.150.201.1634:porti48000eed2:ip14:31.192.237.1204:porti41722eed2:ip13:45.41.206.1214:porti53391eed2:ip15:142.113.144.1824:porti51765eed2:ip14:176.114.248.344:porti51453eed2:ip14:176.226.164.724:porti5555eed2:ip14:188.156.237.204:porti49164eed2:ip15:185.194.143.2014:porti26111eed2:ip13:62.153.208.984:porti3652eed2:ip13:185.149.90.214:porti51055eed2:ip15:189.245.179.2534:porti51765eed2:ip13:188.126.89.804:porti27049eed2:ip13:185.253.96.584:porti60246eed2:ip15:146.158.111.1894:porti51413eed2:ip13:93.221.180.904:porti6881eed2:ip14:199.168.73.1264:porti1330eed2:ip12:73.183.68.194:porti51413eed2:ip13:45.13.105.1354:porti51413eed2:ip12:212.7.200.654:porti12665eed2:ip13:82.65.147.1504:porti51414eed2:ip14:45.134.140.1404:porti6881eed2:ip13:51.159.104.654:porti7629eed2:ip14:68.134.157.2134:porti51413eed2:ip13:41.225.80.1504:porti1821eed2:ip14:120.229.36.2134:porti56339eed2:ip13:92.43.185.1114:porti61598eed2:ip12:31.22.89.1114:porti6897eed2:ip12:51.68.81.2274:porti6881eed2:ip12:185.148.1.834:porti52528eed2:ip12:189.6.26.1234:porti51413eed2:ip12:151.95.0.2464:porti51413eed2:ip13:87.227.189.894:porti55000eed2:ip15:185.213.154.1794:porti40455eed2:ip12:108.26.1.1554:porti6942eed2:ip12:62.12.77.1524:porti51413eed2:ip13:95.24.217.1984:porti35348eed2:ip12:85.29.89.1274:porti6881eed2:ip13:81.200.30.1344:porti13858eeee";
    private List<Socket> activeSockets = new ArrayList<>();
    private Queue<Integer> pieceQueue;
    private boolean[] completedPieces;
    private ExecutorService connectionThreadPool;
    private final int maxBlockSize = 16384;
    private int downloaded;
    private int piecesLeft;
    private ByteBuffer[] pieceBuffers;


	public static void main(String[] args) {
		SpringApplication.run(TorrentClientApplication.class, args);
	}
	
	@Override
	public void run(String... args) {

        String filePath = "torrentfile/debian.torrent";
        try {
            Torrent torrent = createTorrentObjectFromFile(filePath);
            String requestUrl = torrent.createRequestURL();
            String responseWithPeerList = requestResponseWithPeerList(requestUrl);
            Handshake handshake = new Handshake(torrent.getInfoHash(), torrent.getPeerIdBytes());
            List<Peer> peerList = makePeerListFromResponse(responseWithPeerList);
            initializePieceQueue(torrent);
            int numThreads = 1; // You can adjust the number of threads for concurrent connections
            connectionThreadPool = Executors.newFixedThreadPool(numThreads);
            for (Peer peer : peerList) {
                connectionThreadPool.submit(() -> {
                    Client client = new Client(peer, handshake);
                    if (client.setClient()) {
						attemptDownloadPiece(client, torrent);
					}
                });
            }
            
        } catch (IOException e) {
            e.printStackTrace();
        }
	}
	
	private void attemptDownloadPiece(Client client, Torrent torrent) {
		try {
			client.sendUnchokeMessage();
			client.sendInterestedMessage();
			System.out.println("Sending unchoke and interested message");
		} catch (IOException e1) {
			e1.printStackTrace();
		}
		System.out.println("attempting to download piece");
		System.out.println("queue is empty: " + pieceQueue.isEmpty());
		while (!pieceQueue.isEmpty() && client.isSocketOpen()) {
			Integer pieceIndex = pieceQueue.poll();
			System.out.println("Grabbed pieceIndex: " + pieceIndex + "from queue");
			Bitfield bitfield = client.getBitfieldObject();
			System.out.println("got client bitfield object");
			if (bitfield.hasPiece(pieceIndex)) { 
				System.out.println("bitfield has piece");
				int pieceSize = getPieceSize(pieceIndex,torrent);
				ByteBuffer pieceBuffer = ByteBuffer.allocate(pieceSize);
				int blockSize = maxBlockSize;
				int numOfBlocksInPiece = getNumberOfBlocksInPiece(pieceSize, blockSize);
				int blockIndex=0;
				try {
					while (blockIndex<numOfBlocksInPiece) {
						if (client.isChoked()) {
							client.sendUnchokeMessage();
						} else {
							int begin = blockIndex * blockSize;
							int blockLength = Math.min(blockSize, pieceSize - begin);
							client.sendRequestMessage(blockIndex, begin, blockSize);
							System.out.println("Sending a request to client: " + client.getPeer() + " for pieceIndex:" + pieceIndex + " at begin: " + begin + " with blocksize: " + blockSize);
							byte[] data = client.receiveMessage();
							Message message = Message.createMessageObject(data);
							if (message.getType()==MessageType.PIECE) blockIndex++;
							client.handleMessage(message, blockIndex, pieceBuffer);
						}
					}
					byte[] pieceData = pieceBuffer.array();
					handleDownloadedPiece(pieceIndex, pieceData, torrent);
				} catch (Exception e) {
					pieceQueue.add(pieceIndex);
					e.printStackTrace();
					break;
				}
			} else {
				pieceQueue.add(pieceIndex);
			}
		}
	}
    
	private void handleDownloadedPiece(Integer pieceIndex, byte[] pieceData, Torrent torrent) {
		if (checkIntegrity(pieceIndex, pieceData, torrent)) {
			completedPieces[pieceIndex]=true;
			System.out.println("Piece download succeded for index: " + pieceIndex);
			downloaded++;
			piecesLeft--;
		} else {
			pieceQueue.add(pieceIndex);
			System.out.println("Piece download failed for index: " + pieceIndex);
		}
		if (piecesLeft==0) downloadComplete(torrent);
	}
	
	private void initializePieceQueue(Torrent torrent) {
		int numberOfPieces = torrent.getPieces().length/20;
		downloaded = 0;
		piecesLeft = numberOfPieces;
		pieceQueue = new ArrayDeque<>();
		initializePieceBuffers(torrent,numberOfPieces);
		completedPieces = new boolean[numberOfPieces];
		for (int i=0; i<numberOfPieces;i++) {
			pieceQueue.add(i);
		}
		System.out.println("Initialized pieceQueue with " + numberOfPieces + " number of pieces");
	}
    
    private boolean checkIntegrity(int pieceIndex, byte[] pieceData, Torrent torrent) {
        byte[] calculatedHash = calculateSHA1Hash(pieceData);
        byte[] expectedHash = torrent.getPieceHashes()[pieceIndex];
        return Arrays.equals(calculatedHash, expectedHash);
    }
    
    private byte[] calculateSHA1Hash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return null;
    }
    



	private int getPieceSize(int pieceIndex, Torrent torrent) {
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
		// Send the HTTP GET request and read the response
		StringBuilder response = new StringBuilder();
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(requestUrl).openConnection();
			connection.setRequestMethod("GET");
			response = new StringBuilder();
			int responseCode = connection.getResponseCode();
			if (responseCode == HttpURLConnection.HTTP_OK) {
				BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
				String line;

				while ((line = reader.readLine()) != null) {
					response.append(line);
				}
				reader.close();

				System.out.println("Response:");
				System.out.println(response.toString());
			} else {
				System.out.println("Request failed. Response Code: " + responseCode);
			}

		} catch (MalformedURLException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ProtocolException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			connection.disconnect();
		}
		return response.toString();
	}
	
    // Method to disconnect all active sockets
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
    
    private void initializePieceBuffers(Torrent torrent, int numberOfPieces) {
        pieceBuffers = new ByteBuffer[numberOfPieces];
        for (int i = 0; i < numberOfPieces; i++) {
            pieceBuffers[i] = ByteBuffer.allocate((int) torrent.getPieceLength());
        }
    }
    
    private byte[] mergePieceBuffers() {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        for (ByteBuffer buffer : pieceBuffers) {
            buffer.flip(); // Prepare for reading
            while (buffer.hasRemaining()) {
                outputStream.write(buffer.get());
            }
        }
        return outputStream.toByteArray();
    }
    
    
    private void saveDownloadedDataToFile(byte[] downloadedData, String filePath) {
        try (FileOutputStream fileOutputStream = new FileOutputStream(filePath)) {
            fileOutputStream.write(downloadedData);
            System.out.println("Downloaded data saved to: " + filePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void downloadComplete(Torrent torrent) {
        byte[] downloadedData = mergePieceBuffers();
        String filePath = torrent.getName(); // Change this to your desired file path
        saveDownloadedDataToFile(downloadedData, filePath);
    }

}
