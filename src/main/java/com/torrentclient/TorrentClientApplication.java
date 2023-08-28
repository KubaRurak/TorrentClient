package com.torrentclient;

import java.io.File;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TorrentClientApplication implements CommandLineRunner {
	

	public static void main(String[] args) {
		SpringApplication.run(TorrentClientApplication.class, args);
	}
	
	@Override
	public void run(String... args) {
	    if(args.length < 2) {
	        System.out.println("Usage: java -jar Torrent.jar <torrent-file-path> <save-path>");
	        return;
	    }

	    String torrentFilePath = args[0];
	    String savePath = args[1];
	    
//		String torrentFilePath = "torrentfile/debian-12.1.0-mipsel-netinst.iso.torrent";
//		String savePath = "C:" + File.separator + "torrentfile" + File.separator + "downloads" + File.separator + "backup";
//		System.out.println("Save path: " + savePath);

	    UserClient userClient = new UserClient(torrentFilePath, savePath);
	    userClient.start();
	}
}
