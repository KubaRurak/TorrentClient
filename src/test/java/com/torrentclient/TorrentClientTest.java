package com.torrentclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class TorrentClientTest {

    private final int maxBlockSize = 16384; // 16KB

    @InjectMocks
    TorrentClient clientUnderTest;
    
    @Mock
    Torrent torrent;

    @Mock
    Client client;

    @Test
    public void testPopulateWorkQueue() {
        // Given
        int pieceIndex = 5;
        int pieceSize = 256 * 1024; // 256KB

        client.workQueue = new ConcurrentLinkedQueue<>();

        // When
        clientUnderTest.populateWorkQueue(client, pieceIndex, pieceSize);

        // Then
        Queue<BlockRequest> expectedQueue = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < pieceSize / maxBlockSize; i++) {
        	BlockRequest request = new BlockRequest(pieceIndex, i * maxBlockSize, maxBlockSize);
            expectedQueue.offer(request);
            System.out.println(request.toString());
        }
        int remainder = pieceSize % maxBlockSize;
        if (remainder != 0) {
            expectedQueue.offer(new BlockRequest(pieceIndex, pieceSize - remainder, remainder));
        }

        assertEquals(expectedQueue.size(), client.workQueue.size());

        while (!expectedQueue.isEmpty()) {
            BlockRequest expected = expectedQueue.poll();
            BlockRequest actual = client.workQueue.poll();

            assertEquals(expected.pieceIndex, actual.pieceIndex);
            assertEquals(expected.begin, actual.begin);
            assertEquals(expected.blockLength, actual.blockLength);
        }
    }
    

    @Nested
    class GetPieceSizeTests {
        private long pieceLength = 256 * 1024; // 256KB
        private long overallLength = pieceLength * 10 + pieceLength / 2; // 10.5 pieces

        @BeforeEach
        public void setUp() {
            when(torrent.getPieceLength()).thenReturn(pieceLength);
            lenient().when(torrent.getLength()).thenReturn(overallLength);
            lenient().when(torrent.getPieces()).thenReturn(new byte[11]); // 11 pieces, given the overall length
        }

        @Test
        public void testGetPieceSize_forLastPiece() {
            int pieceIndex = 10; // last piece (0-based index)

            int size = torrent.getPieceSize(pieceIndex);

            assertEquals(pieceLength / 2, size); // The last piece is half the size of the others
        }

        @Test
        public void testGetPieceSize_forRegularPiece() {
            int pieceIndex = 5; // some middle piece

            int size = torrent.getPieceSize(pieceIndex);

            assertEquals(pieceLength, size); // Regular pieces should have the default piece size
        }
    }
}
