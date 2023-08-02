package com.torrentclient;

public class Bitfield {
	
    private byte[] bitfield;

    public Bitfield(byte[] bitfield) {
        this.bitfield = bitfield;
    }

    public boolean hasPiece(int index) {
        int byteIndex = index / 8;
        int offset = index % 8;
        return (bitfield[byteIndex] >> (7 - offset) & 1) != 0;
    }

    public void setPiece(int index) {
        int byteIndex = index / 8;
        int offset = index % 8;
        bitfield[byteIndex] |= (byte) (1 << (7 - offset));
    }
}
