package com.torrentclient;

public class Response {
    private boolean isHandshake;
    private Message message;
    private Handshake handshake;

    public Response(byte[] responseBytes) {
        this.isHandshake = Handshake.isHandshake(responseBytes);

        if (isHandshake) {
            this.handshake = Handshake.fromBytes(responseBytes);
        } else {
            this.message = Message.readMessage(responseBytes);
        }
    }

    public boolean isHandshake() {
        return isHandshake;
    }

    public Message getMessage() {
        return message;
    }

    public Handshake getHandshake() {
        return handshake;
    }
}