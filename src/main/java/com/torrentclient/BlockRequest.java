package com.torrentclient;

import java.util.Objects;

public class BlockRequest {


	public final int pieceIndex;
    public final int begin;
    public final int blockLength;

    public BlockRequest(int pieceIndex, int begin, int blockLength) {
        this.pieceIndex = pieceIndex;
        this.begin = begin;
        this.blockLength = blockLength;
    }
    
    @Override
	public int hashCode() {
		return Objects.hash(begin, blockLength, pieceIndex);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		BlockRequest other = (BlockRequest) obj;
		return begin == other.begin && blockLength == other.blockLength && pieceIndex == other.pieceIndex;
	}
}