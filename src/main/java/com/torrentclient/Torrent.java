package com.torrentclient;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import unet.bencode.variables.BencodeArray;
import unet.bencode.variables.BencodeObject;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Torrent {

    private String announce;
    private List<String> announceList;
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
    private static final Logger logger = LoggerFactory.getLogger(Torrent.class);


    public Torrent(BencodeObject bencode) {
        this.announce = bencode.getString("announce");
        this.announceList = createAnnounceList(bencode.getBencodeArray("announce-list"));
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
    
    public List<String> createAnnounceList(BencodeArray announceListArray){
    	this.announceList = new ArrayList<>();
//        if (announceListArray != null) {
//            this.announceList = new ArrayList<>();
//            for (int i = 0; i < announceListArray.size(); i++) {
//                BencodeArray sublist = announceListArray.getBencodeArray(i);
//                if (sublist != null) {
//                    for (int j = 0; j < sublist.size(); j++) {
//                        String announceUrl = sublist.getString(j);
//                        this.announceList.add(announceUrl);
//                    }
//                }
//            }
//        }
        announceList.add(this.announce);
        return announceList;
    }
    
    public static Torrent fromFile(String filePath) {
        try {
            BencodeObject bencode = loadFromFile(filePath);
            logger.info("Initializing torrent file");
            return new Torrent(bencode);
        } catch (Exception e) {
            logger.error("Failed to load torrent from file: " + filePath, e);
            return null;
        }
    }

    private static BencodeObject loadFromFile(String filePath) throws IOException {
        byte[] torrentData = Files.readAllBytes(Paths.get(filePath));
        return new BencodeObject(torrentData);
    }
    
    private String generatePeerId() {
    	StringBuilder sb = new StringBuilder();
    	for (int i=0; i<20; i++) {
    		sb.append((int)Math.random()*10);
    	}
    	return sb.toString();
	}

	public List<String> createRequestURLs() {
        String encodedInfoHash = urlEncode(infoHash);
        int port = 12345;
        long uploaded = 0;
        long downloaded = 0;
        long left = this.length;
        
        List<String> requestUrls = new ArrayList<>();
        for (String announce : announceList) {
            String query = String.format("%s?info_hash=%s&peer_id=%s&port=%d&uploaded=%d&downloaded=%d&left=%d",
                    announce, encodedInfoHash, this.peerId, port, uploaded, downloaded, left);
            requestUrls.add(query);
        } 
        return requestUrls;

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
    
    public int getPieceSize(int pieceIndex) {
        long numPieces = this.pieceHashes.length;
        if (pieceIndex == numPieces - 1) {
            int remainingData = (int) (this.getLength() % this.getPieceLength());
            return (remainingData > 0) ? remainingData : (int) this.getPieceLength();
        } else {
            return (int) this.getPieceLength();
        }
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