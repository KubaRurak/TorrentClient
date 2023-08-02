package com.torrentclient;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;
import unet.bencode.variables.BencodeArray;
import unet.bencode.variables.BencodeObject;

@Data
public class Peer {
    private String ipAddress;
    private int port;

    public Peer(String ipAddress, int port) {
        this.ipAddress = ipAddress;
        this.port = port;
    }
    
    public static List<Peer> makePeerList(BencodeObject bencodeResponse) {
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
}
