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
	
    private final PieceMessageCallback pieceMessageCallback;
    private final ClientExceptionCallback clientExceptionCallback;
    
	private Peer peer;
	private Handshake handshake;
	private byte[] infoHash;
	private byte[] peerId;
	private boolean handshakeCompleted;
	private boolean isChoked=true;
	private Bitfield bitfield;
	private boolean clientSetSuccessfully;
	private Socket socket;
	private Torrent torrent;
	
    public Queue<BlockRequest> workQueue;
    public int currentOutstandingRequests;
    public final static int MAX_OUTSTANDING_REQUESTS = 5; 
    public Set<BlockRequest> outstandingRequests;
	public Map<Integer,ByteBuffer> pieceBuffers;

    private static final Logger logger = LoggerFactory.getLogger(Client.class);

	public Client(Torrent torrent, Peer peer, Handshake handshake, PieceMessageCallback pieceMessageCallback, ClientExceptionCallback clientExceptionCallback) {
		this.torrent = torrent;
		this.pieceMessageCallback = pieceMessageCallback;
		this.clientExceptionCallback = clientExceptionCallback;
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
	                clientSetSuccessfully = true;
	            }
	        }
	        
	    } catch (SocketTimeoutException e) {
	        logger.debug("Socket timeout occurred during communication in initializeConnection");
	    } catch (IOException e) {
	        logger.debug("IO exception in initializeConnection");	
	    } finally {
	        adjustSocketTimeout(10000);
	    }
	    
	    return clientSetSuccessfully;
	}

	private void adjustSocketTimeout(int timeout) {
	    try {
	        socket.setSoTimeout(timeout);
	    } catch (SocketException e) {
	        logger.debug("Connection with peer closed");
	    }
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
			logger.debug("HANDSHAKE COMPLETE: " + this.handshakeCompleted);
			return true;
		}
		return false;
	}

	private boolean receiveBitfield() {
	    try {
	        while (handshakeCompleted) {
	            logger.debug("Trying to receive message");
	            byte[] messageResponse = receiveMessage();
	            
	            if (messageResponse != null) {
	                Response parsedResponse = new Response(messageResponse);
	                Message receivedMessage = parsedResponse.getMessage();
	                handleMessage(receivedMessage);
	                
	                if (this.bitfield != null) {
	                    logger.debug("Got bitfield and handshake from peer: " + peer.getIpAddress() + ":" + peer.getPort());
	                    return true;
	                }
	            } else {
	                logger.debug("No response from peer: " + peer.getIpAddress() + ":" + peer.getPort());
	                return false;
	            }
	        }
	    } catch (IOException e) {
	        logger.debug("Error receiving bitfield: " + e.getMessage());
	    }
	    return false;
	}
	



	private void connectToPeer() throws IOException {
		String peerIP = this.peer.getIpAddress();
		int peerPort = this.peer.getPort();
		this.socket = new Socket();
		socket.connect(new InetSocketAddress(peerIP, peerPort), 3000);
		logger.debug("Connected to peer Ip: " + peerIP);
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

	    logger.debug("Received handshake: " + Arrays.toString(buffer));
	    return buffer;
	}
	
	public Message receiveAndParseMessage() throws IOException, SocketException {
	    byte[] data = this.receiveMessage();

	    if(data == null) {
	        logger.debug("Received a keep-alive message or empty data.");
	    } else {
	        logger.debug("Received message with length: " + data.length);
	    }

	    return Message.createMessageObject(data);
	}

	public byte[] receiveMessage() throws IOException, SocketException {
		
		logger.debug("Trying to receive message");
	    InputStream inputStream = socket.getInputStream();

	    byte[] lengthBuffer = new byte[4];
	    int bytesRead = inputStream.read(lengthBuffer);
	    
	    if (bytesRead == -1) {
	        logger.debug("Input stream closed by the other end.");
	        throw new IOException("Connection closed by the other end.");
	    }

	    if (bytesRead != 4) {
	        logger.debug("Expected to read 4 bytes for message length but got: " + bytesRead);
	        throw new IOException("Unexpected number of bytes read for message length: " + bytesRead);
	    }

	    int length = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.BIG_ENDIAN).getInt();

	    if (length == 0) {
	        logger.debug("Received a keep-alive message with 0 length.");
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

	        logger.debug("Read " + bytesRead + " bytes. Total bytes read so far: " + totalBytesRead);
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
	

	private void sendMessage(Message message) throws IOException {
		OutputStream outputStream = socket.getOutputStream();
		byte[] messageBytes = message.serialize();
		outputStream.write(messageBytes);
		logger.debug(bytesToHex(messageBytes));
		outputStream.flush();
	}
	
	
	private static String bytesToHex(byte[] bytes) {
	    StringBuilder sb = new StringBuilder();
	    for (byte b : bytes) {
	        sb.append(String.format("%02X ", b));
	    }
	    return sb.toString();
	}
	
	
	public void handleMessage(Message message)  {
		
		try {
			switch (message.getType()) {
			case KEEP_ALIVE:
				socket.setSoTimeout(5000);
				logger.debug("Got keep alive");
				break;
			case BITFIELD:
				this.bitfield = new Bitfield(message.getPayload());
				break;
			case CHOKE:
				logger.debug("GOT CHOKE MESSAGE");
				socket.setSoTimeout(5000);
				this.isChoked=true;
				sendUnchokeMessage();
				break;
			case UNCHOKE:
				logger.debug("GOT UNCHOKED MESSAGE");
				socket.setSoTimeout(15000);
				this.isChoked=false;
				break;
			case PIECE:
				socket.setSoTimeout(15000);
			    pieceMessageCallback.onPieceMessageReceived(message, this);
			    break;
			case HAVE:
				handleHaveMessage(message);
				break;
			default:
				logger.debug("Got a message of type: " + message.getType());
				break;
			}
		} catch (IOException | WrongMessageTypeException | WrongPayloadLengthException e) {
	        clientExceptionCallback.onException(this, e);
		}
	}

	

	private void handleHaveMessage(Message message) throws WrongMessageTypeException, WrongPayloadLengthException {
		int index = Message.parseHaveMessage(message);
		this.bitfield.setPiece(index);
	}
	

	public boolean isSocketOpen() {
	    return socket != null && !socket.isClosed();
	}
	
    public void closeConnection() {
        try {
            if (socket != null) {
                socket.close();
            }
            logger.debug("Connection successfully closed.");
        } catch (IOException e) {
            logger.debug("Error closing connection.");
        }
    }


	@Override
	public String toString() {
		int socketTimeout = -1;
		try {
			socketTimeout = this.getSocket().getSoTimeout();
		} catch (SocketException e) {
			e.printStackTrace();
		}
		
		return "Client [peer=" + peer + ", isChoked=" + isChoked + ", socket timeout="  + socketTimeout
				+ ", clientSetSuccessfully=" + clientSetSuccessfully + ", socket=" + socket 
				+ ", workQueue=" + workQueue + ", currentOutstandingRequests=" + currentOutstandingRequests
				+ ", outstandingRequests=" + outstandingRequests + ", pieceBuffers=" + pieceBuffers.size() + "]";
	}


    public boolean isIdle() {
        return (workQueue.isEmpty() && outstandingRequests.size() == 0) || isChoked;
    }
}
