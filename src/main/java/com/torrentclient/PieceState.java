package com.torrentclient;

import java.util.BitSet;

import lombok.Data;
@Data
public class PieceState {
	private final int pieceIndex;
    private final BitSet blocksReceived;
    private final int totalBlocks;
    private boolean isComplete;
    
    
    public PieceState(int totalBlocks, int pieceIndex) {
    	this.pieceIndex = pieceIndex;
        this.blocksReceived = new BitSet(totalBlocks);
        this.totalBlocks = totalBlocks;
        this.isComplete = false;
    }

    public void markBlockReceived(int blockIndex) {
        blocksReceived.set(blockIndex);
        if (isPieceComplete()) markComplete();
    }

    public boolean isPieceComplete() {
        return blocksReceived.cardinality() == totalBlocks;
    }

	public int getPieceIndex() {
		return pieceIndex;
	}
	
	public int getNumBlocksReceived() {
		return blocksReceived.cardinality();
	}

	public void markComplete() {
		isComplete = true;
		
	}


}