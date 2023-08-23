package com.torrentclient;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TorrentTest {

    private static final long LENGTH = 533172224L;
    private static final long PIECE_LENGTH = 262144L;
    private static final int LAST_PIECE_SIZE = 233472;

    private Torrent torrent;

    @BeforeEach
    public void setUp() {
    	torrent = Torrent.fromFile("torrentfile/debian-12.1.0-mipsel-netinst.iso.torrent");
    }

    @Test
    public void testGetPieceSize() {
        // Test for a regular piece
        assertEquals(PIECE_LENGTH, torrent.getPieceSize(1000));

        // Test for the last piece
        assertEquals(LAST_PIECE_SIZE, torrent.getPieceSize(2033));

        // Test for a piece index that doesn't exist (assuming negative value returns full piece size)
        assertEquals(PIECE_LENGTH, torrent.getPieceSize(3000));
    }
}

