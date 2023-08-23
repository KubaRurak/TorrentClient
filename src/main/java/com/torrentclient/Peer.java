package com.torrentclient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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

    public static List<Peer> makePeerListFromResponse(String responseWithPeerList) {
        BencodeObject bencodeResponse = new BencodeObject(responseWithPeerList.getBytes());
        return makePeerListFromBencode(bencodeResponse);
    }

    public static String requestResponseWithPeerList(String requestUrl) {
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

    private static StringBuilder readResponse(HttpURLConnection connection) throws IOException {
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

    public static List<Peer> makePeerListFromBencode(BencodeObject bencodeResponse) {
        List<Peer> peers = new ArrayList<>();
        BencodeArray peersArray = bencodeResponse.getBencodeArray("peers");
        if (peersArray != null) {
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
        }
        peers.stream().forEach(peer -> System.out.println("ip: " + peer.getIpAddress() + " port: " + peer.getPort()));
        return peers;
    }
    
    public static List<Peer> fetchPeers(String requestUrl) {
        String responseWithPeerList = requestResponseWithPeerList(requestUrl);
        return makePeerListFromResponse(responseWithPeerList);
    }
}
