package com.torrentclient;

import lombok.Data;

@Data
public class PieceMessageInfo {
    private final int pieceIndex;
    private final int begin; // or beginOffset
    private final int blockLength;

    public PieceMessageInfo(int pieceIndex, int begin, int blockLength) {
        this.pieceIndex = pieceIndex;
        this.begin = begin;
        this.blockLength = blockLength;
    }
}