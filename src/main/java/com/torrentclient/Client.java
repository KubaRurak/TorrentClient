package com.torrentclient;

import java.io.ByteArrayOutputStream;
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
import java.util.BitSet;

public class Client {

	private Peer peer;
	private Handshake handshake;
	private byte[] infoHash;
	private byte[] peerId;
	private boolean handshakeCompleted;
	private boolean isChoked=false;
	private byte[] bitfield;
	private boolean clientSetSuccessfully;
	private Socket socket;




	public Client(Peer peer, Handshake handshake) {
		this.peer = peer;
		this.handshake=handshake;
	}


	public boolean setClient() throws SocketException {
		try {
			connectToPeer();
			socket.setSoTimeout(3000);
			performHandshake();
			byte[] response = receiveHandshake();
			if (response != null) {
				Response parsedResponse = new Response(response);
				System.out.println("Response: " + new String(response));
				if (parsedResponse.isHandshake()) {
					Handshake receivedHandshake = Handshake.fromBytes(response);
					this.peerId = receivedHandshake.getPeerId();
					this.infoHash = receivedHandshake.getInfoHash();
					this.handshakeCompleted = true;
				}
				System.out.println("HANDSHAKE COMPLETE: " + this.handshakeCompleted);
				while (handshakeCompleted) {
					System.out.println("Trying to recieve message");
					byte[] messageResponse = receiveMessage();
					if (messageResponse != null) {
						System.out.println("Received message from peer: " + peer.getIpAddress() + ":" + peer.getPort());
						parsedResponse = new Response(messageResponse);
						Message receivedMessage = parsedResponse.getMessage();
						System.out.println("Message type is: " + receivedMessage.getType().toString());
						handleMessage(receivedMessage);
						if (this.bitfield!=null) {
							System.out.println("Success, can request pieces from peer");
							clientSetSuccessfully = true;
							socket.setSoTimeout(0);
							break;
						}
					} else {
						System.out.println("No response from peer: " + peer.getIpAddress() + ":" + peer.getPort());
						disconnect();
						break; // Break the loop if there's no more data to read
					}
				}
			}
		} catch (IOException e) {
			System.out.println("Client timed out");
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return clientSetSuccessfully;

	}



	private void connectToPeer() throws IOException {
		String peerIP = this.peer.getIpAddress();
		int peerPort = this.peer.getPort();
		this.socket = new Socket();
		socket.connect(new InetSocketAddress(peerIP, peerPort), 3000);
		System.out.println("Connected to peer Ip: " + peerIP);
	}

	private void disconnect() {
		try {
			socket.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void performHandshake() throws IOException {
		OutputStream outputStream = socket.getOutputStream();
		byte[] handshakeMessage = handshake.createHandshake();
		outputStream.write(handshakeMessage);
		outputStream.flush();
	}
	private byte[] receiveHandshake() throws IOException {
		InputStream inputStream = socket.getInputStream();
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
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

		outputStream.write(buffer, 0, totalBytesRead);
		byte[] response = outputStream.toByteArray();
		System.out.println("response " + Arrays.toString(response));

		return response;
	}
	private byte[] receiveMessage() throws IOException {
		InputStream inputStream = socket.getInputStream();
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		// Read the first four bytes to get the length
		byte[] lengthBuffer = new byte[4];
		int bytesRead = inputStream.read(lengthBuffer);
		if (bytesRead != 4) {
			return null;
		}
		// Convert the 4 bytes in lengthBuffer to an integer
		int length = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.BIG_ENDIAN).getInt();
		int typeByte = inputStream.read();
		if (typeByte == -1) {
			return null;
		}
		int payloadLength = length - 1;
		byte[] buffer = new byte[payloadLength];
		int totalBytesRead = 0;
		while (totalBytesRead < payloadLength) {
			bytesRead = inputStream.read(buffer, totalBytesRead, payloadLength - totalBytesRead);
			if (bytesRead == -1) {
				// Connection closed prematurely
				return null;
			}
			totalBytesRead += bytesRead;
		}
		outputStream.write(typeByte);
		outputStream.write(buffer);
		return outputStream.toByteArray();
	}
	
	private void sendRequestMessage(int index, int begin, int length) throws IOException {
		Message requestMessage = Message.createRequestMessage(index, begin, length);
		sendMessage(requestMessage);
	}
	
	private void sendUnchokeMessage() throws IOException {
		Message unchokeMessage = Message.createUnchokeMessage();
		sendMessage(unchokeMessage);
	}


	private void sendMessage(Message message) throws IOException {
		OutputStream outputStream = socket.getOutputStream();
		byte[] messageBytes = message.serialize();
		outputStream.write(messageBytes);
		outputStream.flush();
	}
	

	private void handleMessage(Message message) throws Exception {
		
		switch (message.getType()) {
		case BITFIELD:
			this.bitfield = message.getPayload();
			break;
		case CHOKE:
			this.isChoked=true;
			try {
				sendUnchokeMessage();
			} catch (IOException e) {
				e.printStackTrace();
			}
			break;
		case UNCHOKE:
			this.isChoked=false;
		case PIECE:
			handlePieceMessage(message);
		default:
			System.out.println("Got a message different than payload or choke/unchoke");
			break;
		}
	}


	private void handlePieceMessage(Message message) {
		// TODO Auto-generated method stub
		
	}

}