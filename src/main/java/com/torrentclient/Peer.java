package com.torrentclient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.Data;
import unet.bencode.variables.BencodeArray;
import unet.bencode.variables.BencodeObject;

@Data
public class Peer {
    private String ipAddress;
    private int port;
    private static final Logger logger = LoggerFactory.getLogger(Peer.class);


    public Peer(String ipAddress, int port) {
        this.ipAddress = ipAddress;
        this.port = port;
    }

    public static byte[] requestResponseWithPeerList(String requestUrl) {
        HttpURLConnection connection = null;
        byte response[] = null;

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

        return response;
    }
    
    private static byte[] readResponse(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            return out.toByteArray();
        }
    }
    public static List<Peer> makePeerList(byte[] responseWithPeerList) {
        List<Peer> peers = new ArrayList<>();
        BencodeObject bencodeResponse = new BencodeObject(responseWithPeerList);
        Object peersElement = getApropriateObject(bencodeResponse, "peers");
        if (peersElement instanceof BencodeArray) {
            BencodeArray peersArray = bencodeResponse.getBencodeArray("peers");
            for (int i = 0; i < peersArray.size(); i++) {
                BencodeObject peerObj = peersArray.getBencodeObject(i);
                if (peerObj != null) {
                    byte[] ipBytes = peerObj.getBytes("ip");
                    String ip = new String(ipBytes);
                    int port = peerObj.getInteger("port");
                    Peer peer = new Peer(ip, port);
                    peers.add(peer);
                }
            }
        } else if (peersElement instanceof byte[]) {
            byte[] peersBytes = bencodeResponse.getBytes("peers");
            for (int i = 0; i < peersBytes.length; i += 6) {
                String ip = String.format("%d.%d.%d.%d", peersBytes[i] & 0xFF, peersBytes[i+1] & 0xFF, peersBytes[i+2] & 0xFF, peersBytes[i+3] & 0xFF);
                int port = ((peersBytes[i+4] & 0xFF) << 8) | (peersBytes[i+5] & 0xFF);
                peers.add(new Peer(ip, port));
            }
        }
        logger.info("Peer list created succesfully");

//        peers.stream().forEach(peer -> System.out.println("ip: " + peer.getIpAddress() + " port: " + peer.getPort()));
        return peers;
    }
    

    
    public static List<Peer> fetchPeers(List<String> requestUrls) {
        List<Peer> allPeers = new ArrayList<>();
        for (String requestUrl : requestUrls) {
            byte[] responseWithPeerList = requestResponseWithPeerList(requestUrl);
            List<Peer> peers = makePeerList(responseWithPeerList);
            allPeers.addAll(peers);
        }
        return allPeers;
    }
    
    public static Object getApropriateObject(BencodeObject bencodeObject, String key) {
        try {
            return bencodeObject.getBencodeArray(key);
        } catch (ClassCastException e) {
            return bencodeObject.getBytes(key);
        }
    }
}
