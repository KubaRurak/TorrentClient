package com.torrentclient;

import java.util.BitSet;

public class Bitfield {
    
    private BitSet bits;
    
    public Bitfield(byte[] bitfield) {
        this.bits = byteArrayToBitSet(bitfield);
    }
    
    public boolean hasPiece(int index) {
        return bits.get(index);
    }
    
    public void setPiece(int index) {
        bits.set(index);
    }

    public int cardinality() {
        return bits.cardinality();
    }

    private BitSet byteArrayToBitSet(byte[] bytes) {
        BitSet bitset = new BitSet(bytes.length * 8);
        for (int i = 0; i < bytes.length * 8; i++) {
            if ((bytes[bytes.length - i / 8 - 1] & (1 << (i % 8))) > 0) {
                bitset.set(i);
            }
        }
        return bitset;
    }
    
    public byte[] toByteArray() {
        byte[] bytes = new byte[(bits.length() + 7) / 8];
        for (int i = 0; i < bits.length(); i++) {
            if (bits.get(i)) {
                bytes[bytes.length - i / 8 - 1] |= 1 << (i % 8);
            }
        }
        return bytes;
    }
}
