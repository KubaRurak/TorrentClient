package com.torrentclient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.torrentclient.exceptions.WrongMessageTypeException;
import com.torrentclient.exceptions.WrongPayloadLengthException;

import lombok.Data;

@Data
public class Client {
	
    private final PieceMessageCallback callback;
    
	private Peer peer;
	private Handshake handshake;
	private byte[] infoHash;
	private byte[] peerId;
	private boolean handshakeCompleted;
	private boolean isChoked=true;
	private byte[] bitfield;
	private boolean clientSetSuccessfully;
	private Socket socket;
	private Torrent torrent;
	
    public Queue<BlockRequest> workQueue;
    public int currentOutstandingRequests;
    public final static int MAX_OUTSTANDING_REQUESTS = 5; 
    public Set<BlockRequest> outstandingRequests;
	public Map<Integer,ByteBuffer> pieceBuffers; // pieceIndex, buffer

    private static final Logger logger = LoggerFactory.getLogger(Client.class);




	public Client(Torrent torrent, Peer peer, Handshake handshake, PieceMessageCallback callback) {
		this.torrent = torrent;
		this.callback = callback;
		this.peer = peer;
		this.handshake=handshake;
        this.workQueue = new ConcurrentLinkedQueue<>(); 
        this.currentOutstandingRequests = 0;
        this.outstandingRequests = Collections.synchronizedSet(new HashSet<>());
        this.pieceBuffers = new HashMap<>();
	}


	public boolean initializeConnection() {
	    try {
	        connectToPeer();
	        socket.setSoTimeout(5000);
	        
	        if (performHandshake()) {
	            if (receiveBitfield()) {
//	            	sendBitfieldMessage();
	                clientSetSuccessfully = true;
	            }
	        }
	        
	    } catch (SocketTimeoutException e) {
	        logger.info("Socket timeout occurred during communication in setClient");
	    } catch (IOException e) {
	        logger.info("Some IO exception in setClient");
	    } catch (Exception e) {
	        logger.info("Some other exception in setClient");
	    } finally {
	        try {
	            socket.setSoTimeout(90000);
	        } catch (SocketException e) {
	            logger.info("Error adjusting socket timeout in the finally block in setClient");
	        }
	    }
	    
	    return clientSetSuccessfully;
	}

	private boolean performHandshake() throws IOException {
		sendHandshake();
		byte[] response = receiveHandshake();

		if (response != null) {
			Response parsedResponse = new Response(response);
			if (parsedResponse.isHandshake()) {
				Handshake receivedHandshake = Handshake.fromBytes(response);
				this.peerId = receivedHandshake.getPeerId();
				this.infoHash = receivedHandshake.getInfoHash();
				this.handshakeCompleted = true;
			}
			logger.info("HANDSHAKE COMPLETE: " + this.handshakeCompleted);
			return true;
		}
		return false;
	}

	private boolean receiveBitfield() {
	    try {
	        while (handshakeCompleted) {
	            logger.info("Trying to receive message");
	            byte[] messageResponse = receiveMessage();
	            
	            if (messageResponse != null) {
	                Response parsedResponse = new Response(messageResponse);
	                Message receivedMessage = parsedResponse.getMessage();
	                handleMessage(receivedMessage);
	                
	                if (this.bitfield != null) {
	                    logger.info("Got bitfield and handshake from peer: " + peer.getIpAddress() + ":" + peer.getPort());
	                    return true;
	                }
	            } else {
	                logger.info("No response from peer: " + peer.getIpAddress() + ":" + peer.getPort());
	                return false;
	            }
	        }
	    } catch (Exception e) {
	        logger.error("Error receiving bitfield: " + e.getMessage());
	    }
	    return false;
	}
	



	private void connectToPeer() throws IOException {
		String peerIP = this.peer.getIpAddress();
		int peerPort = this.peer.getPort();
		this.socket = new Socket();
		socket.connect(new InetSocketAddress(peerIP, peerPort), 3000);
		logger.info("Connected to peer Ip: " + peerIP);
	}

	public void disconnect() {
		try {
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void sendHandshake() throws IOException {
		OutputStream outputStream = socket.getOutputStream();
		byte[] handshakeMessage = handshake.createHandshake();
		outputStream.write(handshakeMessage);
		outputStream.flush();
	}
	private byte[] receiveHandshake() throws IOException {
	    InputStream inputStream = socket.getInputStream();
	    byte[] buffer = new byte[68];
	    int totalBytesRead = 0;

	    // Read up to 68 bytes for the handshake
	    while (totalBytesRead < 68) {
	        int bytesRead = inputStream.read(buffer, totalBytesRead, 68 - totalBytesRead);
	        if (bytesRead == -1) {
	            // Connection closed prematurely
	            return null;
	        }
	        totalBytesRead += bytesRead;
	    }

	    logger.info("Received handshake: " + Arrays.toString(buffer));
	    return buffer;
	}
	
	public Message receiveAndParseMessage() throws IOException {
	    byte[] data = this.receiveMessage();

	    if(data == null) {
	        logger.info("Received a keep-alive message or empty data.");
	    } else {
	        logger.info("Received message with length: " + data.length);
	    }

	    return Message.createMessageObject(data);
	}

	public byte[] receiveMessage() throws IOException {
		
		socket.setSoTimeout(150000);
		logger.info("Trying to receive message");
	    InputStream inputStream = socket.getInputStream();

	    byte[] lengthBuffer = new byte[4];
	    int bytesRead = inputStream.read(lengthBuffer);
	    
	    if (bytesRead == -1) {
	        logger.info("Input stream closed by the other end.");
	        throw new IOException("Connection closed by the other end.");
	    }

	    if (bytesRead != 4) {
	        System.err.println("Expected to read 4 bytes for message length but got: " + bytesRead);
	        throw new IOException("Unexpected number of bytes read for message length: " + bytesRead);
	    }

	    int length = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.BIG_ENDIAN).getInt();

	    if (length == 0) {
	        logger.info("Received a keep-alive message with 0 length.");
	        return null; // This indicates a keep-alive message.
	    }

	    byte[] message = new byte[length];
	    int totalBytesRead = 0;

	    while (totalBytesRead < length) {
	        bytesRead = inputStream.read(message, totalBytesRead, length - totalBytesRead);

	        if (bytesRead == -1) {
	            System.err.println("Connection closed prematurely after reading " + totalBytesRead + " bytes of a " + length + "-byte message.");
	            throw new IOException("Connection closed prematurely.");
	        }

	        totalBytesRead += bytesRead;

	        logger.info("Read " + bytesRead + " bytes. Total bytes read so far: " + totalBytesRead);
	    }

	    return message;
	}
	
	public void sendRequestMessage(int index, int begin, int length) throws IOException {
		Message requestMessage = Message.createRequestMessage(index, begin, length);
		sendMessage(requestMessage);
	}
	
	public void sendRequestMessage(BlockRequest blockRequest) throws IOException {
		int index = blockRequest.getPieceIndex();
		int begin = blockRequest.getBegin();
		int length = blockRequest.getBlockLength();
		Message requestMessage = Message.createRequestMessage(index, begin, length);
		sendMessage(requestMessage);
	}
	
	public void sendUnchokeMessage() throws IOException {
		Message unchokeMessage = Message.createUnchokeMessage();
		sendMessage(unchokeMessage);
	}
	
	public void sendInterestedMessage() throws IOException {
		Message interestedMessage = Message.createInterestedMessage();
		sendMessage(interestedMessage);
	}
	
	public void sendBitfieldMessage() throws IOException {
		byte[] bitfield = torrent.generateBitfield();
		sendMessage(bitfield);
		
	}




	private void sendMessage(Message message) throws IOException {
		OutputStream outputStream = socket.getOutputStream();
		byte[] messageBytes = message.serialize();
		outputStream.write(messageBytes);
		logger.info(bytesToHex(messageBytes));
		outputStream.flush();
	}
	
	private void sendMessage(byte[] messageBytes) throws IOException {
		OutputStream outputStream = socket.getOutputStream();
		outputStream.write(messageBytes);
		logger.info(bytesToHex(messageBytes));
		outputStream.flush();
	}
	
	private static String bytesToHex(byte[] bytes) {
	    StringBuilder sb = new StringBuilder();
	    for (byte b : bytes) {
	        sb.append(String.format("%02X ", b));
	    }
	    return sb.toString();
	}
	

	public void handleMessage(Message message) throws Exception {
		
		switch (message.getType()) {
		case KEEP_ALIVE:
			socket.setSoTimeout(15000);
			break;
		case BITFIELD:
			this.bitfield = message.getPayload();
			break;
		case CHOKE:
			logger.info("GOT CHOKE MESSAGE");
			socket.setSoTimeout(3000);
			this.isChoked=true;
			sendUnchokeMessage();
			break;
		case UNCHOKE:
			logger.info("GOT UNCHOKED MESSAGE");
			socket.setSoTimeout(150000);
			this.isChoked=false;
			break;
		case PIECE:
			logger.info("GOT PIECE MESSAGE");
            callback.onPieceMessageReceived(message, this);
            break;
		case HAVE:
			handleHaveMessage(message);
			break;
		default:
			logger.info("Got a message of type: " + message.getType());
			break;
		}
	}

	

	private void handleHaveMessage(Message message) throws WrongMessageTypeException, WrongPayloadLengthException {
		int index = Message.parseHaveMessage(message);
		logger.info("Got have message for index: " + index);
	}
	

	public boolean isSocketOpen() {
	    return socket != null && !socket.isClosed();
	}

	public Bitfield getBitfieldObject() {
		
		return new Bitfield(this.bitfield);
	}
	
    public void closeConnection() {
        try {
            if (socket != null) {
                socket.close();
            }
            logger.info("Connection successfully closed.");
        } catch (IOException e) {
            logger.info("Error closing connection.");
        }
    }
}
