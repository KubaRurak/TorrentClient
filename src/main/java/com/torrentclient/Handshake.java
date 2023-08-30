package com.torrentclient;

import java.nio.ByteBuffer;
import java.util.Arrays;

import lombok.Data;

@Data
public class Handshake {
	    
	private String pstr;
    private byte[] infoHash;
    private byte[] peerId;
	private byte[] reserved;

    
	public Handshake(byte[] infoHash, byte[] peerId) {
		super();
		this.pstr = "BitTorrent protocol";
		this.infoHash = infoHash;
		this.reserved = new byte[8];
		this.peerId = peerId;
	}
    

	public Handshake(byte[] pstrBytes, byte[] infoHash, byte[] peerId, byte[] reserved) {
		super();
		this.pstr = pstrBytes.toString();
		this.infoHash = infoHash;
		this.peerId = peerId;
		this.reserved = reserved;
	}


	public byte[] createHandshake() {
	    int pstrlen = pstr.length();
	    int handshakeLength = 49 + pstrlen;
	    ByteBuffer handshakeBuffer = ByteBuffer.allocate(handshakeLength);
	    handshakeBuffer.put((byte) pstrlen); // Protocol string length
	    handshakeBuffer.put(pstr.getBytes()); // Protocol identifier
	    handshakeBuffer.put(new byte[8]); // Reserved bytes
	    handshakeBuffer.put(infoHash); // Infohash
	    handshakeBuffer.put(peerId); // Peer ID
	    return handshakeBuffer.array();
		
	}
	
	public static boolean isHandshake(byte[] responseBytes) {
        byte[] protocolBytes = "BitTorrent protocol".getBytes();
        return Arrays.equals(Arrays.copyOfRange(responseBytes, 1, 20), protocolBytes);
	}
	
	
	
    public static Handshake fromBytes(byte[] handshakeBytes) {
        ByteBuffer buffer = ByteBuffer.wrap(handshakeBytes);
        byte pstrlen = buffer.get();
        byte[] pstrBytes = new byte[pstrlen];
        buffer.get(pstrBytes);
        byte[] reservedBytes = new byte[8];
        buffer.get(reservedBytes);
        byte[] infoHash = new byte[20];
        buffer.get(infoHash);
        byte[] peerId = new byte[20];
        buffer.get(peerId);
        return new Handshake(pstrBytes, reservedBytes, infoHash, peerId);
    }
    

}
