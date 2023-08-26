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
    private long totalBytes;
    private ScheduledExecutorService speedLoggerScheduler;
    private Bitfield downloadedPiecesBitfield;
    private static final Logger logger = LoggerFactory.getLogger(SpeedLogger.class);

    public SpeedLogger(int numberOfPieces, Bitfield downloadedPiecesBitfield, long totalBytes) {
        this.numberOfPieces = numberOfPieces;
        this.downloadedPiecesBitfield = downloadedPiecesBitfield;
        this.totalBytes = totalBytes;
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
        }, 1, 2, TimeUnit.SECONDS);
    }


    private void logDownloadStatus() {
        logDownloadSpeed();
        logPiecesProgress();
    }
    
    private void logDownloadSpeed() {
        long currentTime = System.currentTimeMillis();
        long timeElapsed = currentTime - startTime;

        long bytesDownloadedInterval = bytesDownloaded - previousDownloaded;
        double speed = bytesDownloadedInterval / (timeElapsed / 1000.0);
        double speedInKBps = speed / 1024;

        long bytesRemaining = totalBytes - bytesDownloaded;
        double estimatedTimeRemaining = bytesRemaining / speed;  // in seconds

        int hours = (int) (estimatedTimeRemaining / 3600);
        int minutes = (int) ((estimatedTimeRemaining % 3600) / 60);
        int seconds = (int) (estimatedTimeRemaining % 60);

        String estimatedTimeLeft;
        if (hours > 100) {
            estimatedTimeLeft = "NaN";
        } else {
            estimatedTimeLeft = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }

        System.out.print(String.format("\rDownload speed: %.2f KB/s. Estimated time left: %s. ", 
            speedInKBps, estimatedTimeLeft));

        this.previousDownloaded = bytesDownloaded;
        this.startTime = currentTime;
    }
    
    private void logPiecesProgress() {
        int totalPieces = numberOfPieces; 
        int downloaded = downloadedPiecesBitfield.cardinality();
        int remaining = totalPieces - downloaded;
        double progress = ((double) downloaded / totalPieces) * 100;

        int progressBarWidth = 50;
        int progressBarFilled = (int) (progress / (100 / progressBarWidth));

        StringBuilder progressBar = new StringBuilder("[");
        for (int i = 0; i < progressBarWidth; i++) {
            if (i < progressBarFilled) {
                progressBar.append("#");
            } else {
                progressBar.append("-");
            }
        }
        progressBar.append("]");

        System.out.print(String.format("\rPieces downloaded: %d/%d. Remaining: %d. Progress: %.2f%% %s", 
            downloaded, totalPieces, remaining, progress, progressBar.toString()));
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
