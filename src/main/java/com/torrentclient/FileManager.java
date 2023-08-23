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
    private final String torrentName; // Added field

    public FileManager(String storagePath, String torrentName) {
        this.storagePath = storagePath;
        this.torrentName = torrentName;
    }

    public void savePieceToDisk(int pieceIndex, byte[] pieceData) {
        String fragmentFileName = storagePath + File.separator + torrentName + ".piece." + pieceIndex;
        logger.info("Attempting to save Piece {} to disk with name {}", pieceIndex, fragmentFileName);
        logger.info("Saving piece {}", pieceIndex);

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

    public void mergeFiles(int numberOfPieces) throws IOException {
        String outputFile = torrentName;

        // Merge process
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            for (int i = 0; i < numberOfPieces; i++) {
                File pieceFile = new File(storagePath + File.separator + torrentName + ".piece." + i);
                Files.copy(pieceFile.toPath(), fos);
            }
        }

        // If merging is successful, delete the pieces in a second iteration
        for (int i = 0; i < numberOfPieces; i++) {
            File pieceFile = new File(storagePath + File.separator + torrentName + ".piece." + i);
            Files.deleteIfExists(pieceFile.toPath());
        }
    }
}
