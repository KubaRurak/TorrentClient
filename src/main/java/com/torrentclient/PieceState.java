package com.torrentclient;

import java.util.BitSet;

public class PieceState {
	private final int pieceIndex;
    private final BitSet blocksReceived;
    private final int totalBlocks;
    
    public PieceState(int totalBlocks, int pieceIndex) {
    	this.pieceIndex = pieceIndex;
        this.blocksReceived = new BitSet(totalBlocks);
        this.totalBlocks = totalBlocks;
    }

    public void markBlockReceived(int blockIndex) {
        blocksReceived.set(blockIndex);
    }

    public boolean isPieceComplete() {
        return blocksReceived.cardinality() == totalBlocks;
    }

	public int getPieceIndex() {
		return pieceIndex;
	}
}