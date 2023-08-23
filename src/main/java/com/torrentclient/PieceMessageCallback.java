package com.torrentclient;

public interface PieceMessageCallback {
	void onPieceMessageReceived(Message message, Client client);
}