package com.torrentclient;

import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import unet.bencode.variables.BencodeObject;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Torrent {

    private String announce;
    private String name;
    private String comment;
    private String createdBy;
    private LocalDateTime creationDate;
    private Encoding encoding;
    private long length;
    private long pieceLength;
    private byte[] pieces;
    private byte[][] pieceHashes;
    private boolean isPrivate;
    private byte[] infoHash;
    private String peerId;

    public Torrent(BencodeObject bencode) {
        this.announce = bencode.getString("announce");
        this.comment = bencode.getString("comment");
        this.createdBy = bencode.getString("created by");
        long creationDate = bencode.getLong("creation date");
        this.creationDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(creationDate), ZoneOffset.UTC);
        BencodeObject infoDict = bencode.getBencodeObject("info");
        this.length = infoDict.getLong("length");
        this.name = infoDict.getString("name");
        this.pieceLength = infoDict.getLong("piece length");
        this.pieces = infoDict.getBytes("pieces");
        byte[] bytesInfo = infoDict.encode();
        this.infoHash = calculateInfoHash(bytesInfo);
        this.pieceHashes = splitPiecesIntoHashes(pieces);
        this.peerId = generatePeerId();
    }
    
    private String generatePeerId() {
    	StringBuilder sb = new StringBuilder();
    	for (int i=0; i<20; i++) {
    		sb.append((int)Math.random()*10);
    	}
    	return sb.toString();
	}

	public String createRequestURL() {
        String encodedInfoHash = urlEncode(infoHash);
        int port = 12345;
        long uploaded = 0;
        long downloaded = 0;
        long left = this.length;

        String query = String.format("%s?info_hash=%s&peer_id=%s&port=%d&uploaded=%d&downloaded=%d&left=%d",
                this.announce, encodedInfoHash, this.peerId, port, uploaded, downloaded, left);
        
        return query;

    }
    
    public byte[] generateBitfield() {
        // Get the number of pieces
        int numberOfPieces = getPieceHashes().length;
        
        // Calculate the number of bytes needed
        int bitfieldBytes = (int) Math.ceil((double) numberOfPieces / 8);
        
        // Create the bitfield, initialized to all zeros
        byte[] bitfield = new byte[bitfieldBytes];
        
        // Create the message, considering 4 bytes for length, 1 for message type
        byte[] data = new byte[bitfieldBytes + 5];
        
        // Set the message length
        ByteBuffer.wrap(data).putInt(bitfieldBytes + 1); // +1 for the message type byte

        // Set the message type (5 for bitfield according to the BitTorrent spec)
        data[4] = 5;
        
        // Copy the bitfield
        System.arraycopy(bitfield, 0, data, 5, bitfieldBytes);
        
        return data;
    }
    
    private byte[] calculateInfoHash(byte[] infoDictBytes) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return sha1.digest(infoDictBytes);
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }
    
    private byte[][] splitPiecesIntoHashes(byte[] pieces) {
        int pieceCount = (int) Math.ceil((double) pieces.length / 20);
        byte[][] hashes = new byte[pieceCount][20];
        for (int i = 0; i < pieceCount; i++) {
            System.arraycopy(pieces, i * 20, hashes[i], 0, 20);
        }
        return hashes;
    }
    
    private String urlEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append("%").append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    public byte[] getPeerIdBytes() {
    	return this.peerId.getBytes();
    }
    
    
    
    @Override
    public String toString() {
        return "Torrent{" +
                "announce='" + announce + '\'' +
                ", name='" + name + '\'' +
                ", comment='" + comment + '\'' +
                ", createdBy='" + createdBy + '\'' +
                ", creationDate=" + creationDate +
                ", length=" + length +
                ", pieceLength=" + pieceLength +
                ", pieces=" + Arrays.toString(pieces) +
                ", isPrivate=" + isPrivate + "\n" +
                ", infoHash Length=" + infoHash.length +

//                ", infoHash=" + Arrays.toString(infoHash) +
//                ", pieceHashes=" + Arrays.deepToString(pieceHashes) +
                '}';
    }
    

    public enum Encoding {
        UTF8,
        UTF16,
        ASCII,
    }


	public byte[] getPieceHash(int pieceIndex) {
		return pieceHashes[pieceIndex];
	}
}