package com.torrentclient;

import lombok.Data;

@Data
public class PieceProgress {
	
	private int index;
	private Client client;
	private byte[] buffer;
	private int downloaded;
	private int requested;
	private int backlog;

}
