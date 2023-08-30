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
    private static final int INTERVAL = 2; //logger interval in seconds

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
        }, 1, INTERVAL, TimeUnit.SECONDS);
    }


    private void logDownloadStatus() {
        long currentTime = System.currentTimeMillis();
        long totalElapsedTime = currentTime - startTime;

        long bytesDownloadedInterval = bytesDownloaded - previousDownloaded;
        double speed = bytesDownloadedInterval / (double) INTERVAL; // speed in bytes per second (2 seconds interval)
        double speedInKBps = speed / 1024;

        long bytesRemaining = totalBytes - bytesDownloaded;
        double averageSpeed = bytesDownloaded / (totalElapsedTime / 1000.0); // average speed in bytes per second
        double estimatedTimeRemaining = bytesRemaining / averageSpeed;  // in seconds
        if (estimatedTimeRemaining < 0) {
            estimatedTimeRemaining = 0;
        }

        int hours = (int) (estimatedTimeRemaining / 3600);
        int minutes = (int) ((estimatedTimeRemaining % 3600) / 60);
        int seconds = (int) (estimatedTimeRemaining % 60);

        String estimatedTimeLeft;
        if (speed == 0 && bytesRemaining==0) {
            estimatedTimeLeft = "00:00:00";
        }else if (speed == 0) {
            estimatedTimeLeft = "NaN"; 
        } else {
            estimatedTimeLeft = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }

        int totalPieces = numberOfPieces; 
        int downloaded = downloadedPiecesBitfield.cardinality();
        double progress = ((double) downloaded / totalPieces) * 100;

        int progressBarWidth = 40;
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

        System.out.print(String.format("\rDownload speed: %.0f KB/s, Estimated time left: %s, Progress: %.2f%% %s", 
            speedInKBps, estimatedTimeLeft, progress, progressBar.toString()));
        System.out.flush();

        this.previousDownloaded = bytesDownloaded;
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
