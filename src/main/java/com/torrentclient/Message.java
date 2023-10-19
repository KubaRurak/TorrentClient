package com.torrentclient;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import com.torrentclient.exceptions.WrongMessageTypeException;
import com.torrentclient.exceptions.WrongPayloadLengthException;

public class Message {
    private MessageType type;
    private byte[] payload;

    public Message(MessageType type, byte[] payload) {
        this.type = type;
        this.payload = payload;
    }
    
    public Message (byte[] messageBytes) {	
        if (messageBytes.length == 4) {
        	this.type = MessageType.KEEP_ALIVE;
        	this.payload = new byte[0];
        }
        int length = ByteBuffer.wrap(messageBytes, 0, 4).getInt();
        byte typeByte = messageBytes[4];
        this.payload = new byte[length - 1];
        this.type = MessageType.fromValue((int) typeByte);
    }
    
    
    public static Message createMessageObject(byte[] messageBytes) {
        if (messageBytes == null) {
            return new Message(MessageType.KEEP_ALIVE, new byte[0]);
        }
        byte typeByte = messageBytes[0];
        MessageType type = MessageType.fromValue((int) typeByte);
        
        byte[] payload = Arrays.copyOfRange(messageBytes, 1, messageBytes.length);
        return new Message(type, payload);
    }
//    
    public static int parseHaveMessage(Message message) throws WrongMessageTypeException, WrongPayloadLengthException {
    	if (message.getType()!=MessageType.HAVE) {
            throw new WrongMessageTypeException("Expected message type HAVE.");
    	}
    	if (message.getPayload().length!=4) {
            throw new WrongPayloadLengthException("Expected payload length of 4 bytes.");
    	}
    	byte[] payload = message.getPayload();
    	int index =  ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).getInt();
    	return index;
    }
    public static PieceMessageInfo parsePieceMessage(int expectedPieceIndex, ByteBuffer buf, Message message) throws Exception {
        if (message.getType() != MessageType.PIECE) {
            throw new Exception("Expected MessageType.PIECE, got" + message.getType());
        }

        if (message.getPayload().length < 8) {
            throw new Exception("Payload too short. %d < 8" + message.getPayload().length);
        }

        int parsedPieceIndex = ByteBuffer.wrap(message.getPayload(), 0, 4).getInt();
        if (parsedPieceIndex != expectedPieceIndex) {
            throw new Exception(String.format("Expected index %d, got %d", expectedPieceIndex, parsedPieceIndex));
        }
        
        int begin = ByteBuffer.wrap(message.getPayload(), 4, 4).getInt();
        if (begin >= buf.capacity()) {
            throw new Exception(String.format("Begin offset too high. %d >= %d", begin, buf.capacity()));
        }

        byte[] data = Arrays.copyOfRange(message.getPayload(), 8, message.getPayload().length);
        if (begin + data.length > buf.capacity()) {
            throw new Exception(String.format("Data too long [%d] for offset %d with capacity %d", data.length, begin, buf.capacity()));
        }

        buf.position(begin);
        buf.put(data);

        return new PieceMessageInfo(parsedPieceIndex, begin, data.length);
    }
    public static int getBeginOffsetFromMessage(Message message) throws Exception {
        if (message.getPayload().length < 8) {
            throw new Exception("Payload too short. %d < 8" + message.getPayload().length);
        }

        int begin = ByteBuffer.wrap(message.getPayload(), 4, 4).getInt();
        return begin;
    }
    
    
    public static Message createRequestMessage(int index, int begin, int length) {
    	byte[] payload = new byte[12];
    	ByteBuffer.wrap(payload, 0, 4).putInt(index);
    	ByteBuffer.wrap(payload, 4, 4).putInt(begin); 
    	ByteBuffer.wrap(payload, 8, 4).putInt(length);
    	return new Message(MessageType.REQUEST,payload);
    }
    
    public static Message createHaveMessage(int index) {
    	byte[] payload = new byte[12];
    	ByteBuffer.wrap(payload, 0, 4).putInt(index);
    	return new Message(MessageType.HAVE,payload);
    }
    
    public static Message createInterestedMessage() {
    	return new Message(MessageType.INTERESTED,new byte[0]);
    }
    
    public static Message createNotInterestedMessage() {
    	return new Message(MessageType.NOT_INTERESTED,new byte[0]);
    }
    
    public static Message createUnchokeMessage() {
    	return new Message(MessageType.UNCHOKE,new byte[0]);
    }
    

	public MessageType getType() {
        return type;
    }

    public byte[] getPayload() {
        return payload;
    }
    
    public byte[] serialize() {
        int length = payload.length+1;
        ByteBuffer buffer = ByteBuffer.allocate(4 + length);
        buffer.putInt(length); 
        buffer.put((byte) type.getValue()); 
        buffer.put(payload); 
        byte[] buffArr = buffer.array();
        return buffArr;
    }

	public static int getPieceIndexFromMessage(Message message) {
        if (message.getType() != MessageType.PIECE) {
            throw new IllegalArgumentException("Expected MessageType.PIECE, got " + message.getType());
        }

        if (message.getPayload().length < 8) {
            throw new IllegalArgumentException("Payload too short. " + message.getPayload().length + " < 8");
        }
        return ByteBuffer.wrap(message.getPayload(), 0, 4).getInt();
	}
	
}
