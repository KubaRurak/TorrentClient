package com.torrentclient;

import lombok.Data;
@Data
public class BlockRequest {


	public final int pieceIndex;
    public final int begin;
    public final int blockLength;

    public BlockRequest(int pieceIndex, int begin, int blockLength) {
        this.pieceIndex = pieceIndex;
        this.begin = begin;
        this.blockLength = blockLength;
    }
    
}