package com.torrentclient;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PeriodicChecker {

    private final ScheduledExecutorService periodicCheckerExecutor;
    private final List<Client> activeClients;
    private Queue<PieceState> pieceQueue;
    private final Bitfield downloadedPiecesBitfield;
    private final Supplier<Boolean> isDownloadCompleteSupplier;
    private static final Logger logger = LoggerFactory.getLogger(PeriodicChecker.class);
    private int blocksPerPiece;

    public PeriodicChecker(List<Client> activeClients, Queue<PieceState> pieceQueue, Bitfield downloadedPiecesBitfield, Supplier<Boolean> isDownloadCompleteSupplier, int blocksPerPiece) {
        this.periodicCheckerExecutor = Executors.newSingleThreadScheduledExecutor();
        this.activeClients = activeClients;
        this.pieceQueue = pieceQueue;
        this.downloadedPiecesBitfield = downloadedPiecesBitfield;
        this.isDownloadCompleteSupplier = isDownloadCompleteSupplier;
        this.blocksPerPiece = blocksPerPiece;
    }

    public void start() {
        periodicCheckerExecutor.scheduleAtFixedRate(this::checkStatus, 0, 3, TimeUnit.SECONDS);
    }

    private void checkStatus() {
        int numClientsIdle=0;
        logger.debug("Periodically checking status");
        logger.debug("Current activeClients: " + activeClients.size());
        for (Client client : activeClients) {
        	if (client.isIdle()) {
        		logger.debug("Client is idle: " + client);
        		numClientsIdle++;
        	}
        }

        if (numClientsIdle>1 && pieceQueue.isEmpty() && !isDownloadComplete()) {
            logger.debug("Clients are idle but the download is not complete.");
            recoverMissingPieces();
        }
    }
    

    private boolean isDownloadComplete() {
        return isDownloadCompleteSupplier.get();
    }

    private void recoverMissingPieces() {
        List<Integer> missingPieces = downloadedPiecesBitfield.getMissingPieces();
        for (int missingPieceIndex : missingPieces) {
            PieceState missingPiece = new PieceState(blocksPerPiece, missingPieceIndex);
            pieceQueue.add(missingPiece);
        }
        logger.debug(missingPieces.size() + " missing pieces re-added to the queue.");
    }
    public void stop() {
        if (periodicCheckerExecutor != null) {
        	logger.debug("periodicChecker shutdown");
            periodicCheckerExecutor.shutdown();
            try {
                if (!periodicCheckerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                	logger.debug("periodicChecker shutdown - await termination");
                    periodicCheckerExecutor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                periodicCheckerExecutor.shutdownNow();
            	logger.debug("periodicChecker - Interrupted exception");
                Thread.currentThread().interrupt();
            }
        }
    }
}