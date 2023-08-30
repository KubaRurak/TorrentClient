package com.torrentclient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileManager {

    private static final Logger logger = LoggerFactory.getLogger(FileManager.class);
    private final String storagePath;
    private final String torrentName;
    private boolean isMerged;

    public FileManager(String storagePath, String torrentName) {
        this.storagePath = storagePath;
        this.torrentName = torrentName;
    }

    public void savePieceToDisk(int pieceIndex, byte[] pieceData) {
        String fragmentFileName = storagePath + File.separator + torrentName + ".piece." + pieceIndex;
        logger.debug("Saving piece {}", pieceIndex);

        // Ensure directories exist
        File file = new File(fragmentFileName);
        File parentDir = file.getParentFile();
        if (!parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (FileOutputStream fos = new FileOutputStream(fragmentFileName)) {
            fos.write(pieceData);
        } catch (IOException e) {
            logger.error("Error writing piece to disk", e);
        }
    }

    public void mergeFiles(int numberOfPieces, long torrentLength) {
        String outputFile = storagePath + File.separator + torrentName;
        System.out.println("\nMerging files");
        // Merge process
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (int i = 0; i < numberOfPieces; i++) {
                File pieceFile = new File(storagePath + File.separator + torrentName + ".piece." + i);
                Files.copy(pieceFile.toPath(), fos);
            }
        } catch (IOException e) {
            logger.debug("Merging failed"); 
            return;  
        }
        File mergedFile = new File(outputFile);
        long expectedSize = torrentLength;
        isMerged=true;
        if (mergedFile.length() == expectedSize) {
        	System.out.println("Merging success, deleting parts");
            for (int i = 0; i < numberOfPieces; i++) {
                File pieceFile = new File(storagePath + File.separator + torrentName + ".piece." + i);
                try {
                    Files.deleteIfExists(pieceFile.toPath());
                } catch (IOException e) {
                	System.out.println("Failed to delete piece file: " + pieceFile.getName());
                }
            }
        } else {
            logger.warn("Merged file size doesn't match expected size. Not deleting piece files.");
        }
        System.out.println("Download succesfull, closing app");
    }
    
    public boolean isPieceDownloaded(int pieceIndex) {
        String pieceFileName = storagePath + File.separator + torrentName + ".piece." + pieceIndex;
        File pieceFile = new File(pieceFileName);
        return pieceFile.exists();
    }
    
    public boolean isFileMerged() {
    	return isMerged;
    }
}
