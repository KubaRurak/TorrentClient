package com.torrentclient;

public class MessageHandler {

    private Message message;

    public MessageHandler(Message message) {
        this.message = message;
    }

    public void handleMessage() {
        MessageType type = message.getType();

        switch (type) {
            case KEEP_ALIVE:
                handleKeepAliveMessage();
                break;
            case CHOKE:
                handleChokeMessage();
                break;
            case UNCHOKE:
                handleUnchokeMessage();
                break;
            case INTERESTED:
                handleInterestedMessage();
                break;
            case NOT_INTERESTED:
                handleNotInterestedMessage();
                break;
            case HAVE:
                handleHaveMessage();
                break;
            case BITFIELD:
                handleBitfieldMessage();
                break;
            case REQUEST:
                handleRequestMessage();
                break;
            case PIECE:
                handlePieceMessage();
                break;
            case CANCEL:
                handleCancelMessage();
                break;
            case PORT:
                handlePortMessage();
                break;
            default:
                // Handle unknown message type
                break;
        }
    }

    private void handleKeepAliveMessage() {
        // Handle keep-alive message
    }

    private void handleChokeMessage() {
        // Handle choke message
    }

    private void handleUnchokeMessage() {
        // Handle unchoke message
    }

    private void handleInterestedMessage() {
        // Handle interested message
    }

    private void handleNotInterestedMessage() {
        // Handle not interested message
    }

    private void handleHaveMessage() {
        // Handle have message
    }

    private void handleBitfieldMessage() {
        // Handle bitfield message
    }

    private void handleRequestMessage() {
        // Handle request message
    }

    private void handlePieceMessage() {
        // Handle piece message
    }

    private void handleCancelMessage() {
        // Handle cancel message
    }

    private void handlePortMessage() {
        // Handle port message
    }
}