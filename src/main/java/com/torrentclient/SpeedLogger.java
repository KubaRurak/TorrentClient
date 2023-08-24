package com.torrentclient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpeedLogger {

    private long startTime;
    private long bytesDownloaded = 0;
    private long previousDownloaded = 0;
    private int numberOfPieces;
    private ScheduledExecutorService speedLoggerScheduler;
    private Bitfield downloadedPiecesBitfield;
    private static final Logger logger = LoggerFactory.getLogger(SpeedLogger.class);

    public SpeedLogger(int numberOfPieces, Bitfield downloadedPiecesBitfield) {
        this.numberOfPieces = numberOfPieces;
        this.downloadedPiecesBitfield = downloadedPiecesBitfield;
    }

    public void start() {
        this.startTime = System.currentTimeMillis();
        this.speedLoggerScheduler = Executors.newSingleThreadScheduledExecutor();
        this.speedLoggerScheduler.scheduleAtFixedRate(() -> {
            try {
                logDownloadStatus();
            } catch (Exception e) {
                logger.error("Error while logging download status", e);
            }
        }, 1, 5, TimeUnit.SECONDS);
    }


    private void logDownloadStatus() {
        logDownloadSpeed();
        logPiecesProgress();
    }
    
    private void logDownloadSpeed() {
        long currentTime = System.currentTimeMillis();
        long timeElapsed = currentTime - startTime;  // time in milliseconds

        long bytesDownloadedInterval = bytesDownloaded - previousDownloaded;  // bytes downloaded during this interval
        double speed = bytesDownloadedInterval / (timeElapsed / 1000.0);  // bytes per second
        double speedInKBps = speed / 1024;  // Convert to KB per second

        logger.info("Download speed: " + String.format("%.2f", speedInKBps) + " KB/s");

        // Update for the next calculation
        this.previousDownloaded = bytesDownloaded;
        this.startTime = currentTime;
    }
    
    private void logPiecesProgress() {
        int totalPieces = numberOfPieces; // Assuming you have this value set already
        int downloaded = downloadedPiecesBitfield.cardinality();
        int remaining = totalPieces - downloaded;
        double progress = ((double) downloaded / totalPieces) * 100;

        logger.info(String.format("Pieces downloaded: %d/%d. Remaining: %d. Progress: %.2f%%", 
            downloaded, totalPieces, remaining, progress));
    }
    
    public synchronized void addBytesDownloaded(long bytes) {
        this.bytesDownloaded += bytes;
    }
    
    public void stop() {
        if (speedLoggerScheduler != null) {
            speedLoggerScheduler.shutdown();
            try {
                if (!speedLoggerScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    speedLoggerScheduler.shutdownNow();
                }
            } catch (InterruptedException ex) {
                speedLoggerScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

}
