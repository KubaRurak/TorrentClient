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
    private final Bitfield downloadedPiecesBitfield;  // Or whatever structure you use to track download status
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
        periodicCheckerExecutor.scheduleAtFixedRate(this::checkStatus, 0, 5, TimeUnit.SECONDS);
    }

    private void checkStatus() {
        boolean allClientsIdle = true;
        logger.debug("Periodically checking status");
        logger.info("Current activeClients: " + activeClients.size());
        for (Client client : activeClients) {
        	if (client.isIdle()) {
        		logger.info("Client is idle: " + client);
        	}
            if (!client.isIdle()) {
            	logger.info("client is not idle: " + client);
                allClientsIdle = false;
            }
        }
    	logger.info("is Download complete: " + isDownloadComplete());
    	logger.info("pieceQueue size is " + pieceQueue.size());
        if (allClientsIdle) {
        	logger.info("All clients are idle!");
        	logger.info("is Download complete: " + isDownloadComplete());
        	logger.info("pieceQueue size is " + pieceQueue.size());
        }
        else logger.info("Not every client is idle");

        if (allClientsIdle && pieceQueue.isEmpty() && !isDownloadComplete()) {
            logger.warn("All clients are idle but the download is not complete.");
            recoverMissingPieces();
        }
    }
    

    private boolean isDownloadComplete() {
        return isDownloadCompleteSupplier.get();
    }

    private void recoverMissingPieces() {
        List<Integer> missingPieces = downloadedPiecesBitfield.getMissingPieces();
        for (int missingPieceIndex : missingPieces) {
            PieceState missingPiece = new PieceState(blocksPerPiece, missingPieceIndex);  // Adjust this instantiation as needed.
            pieceQueue.add(missingPiece);
        }
        logger.info(missingPieces.size() + " missing pieces re-added to the queue.");
    }
    public void stop() {
        if (periodicCheckerExecutor != null) {
        	logger.info("periodicChecker shutdown");
            periodicCheckerExecutor.shutdown();
            try {
                if (!periodicCheckerExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                	logger.info("periodicChecker shutdown - await termination");
                    periodicCheckerExecutor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                periodicCheckerExecutor.shutdownNow();
            	logger.info("periodicChecker - Interrupted exception");
                Thread.currentThread().interrupt();
            }
        }
    }
}