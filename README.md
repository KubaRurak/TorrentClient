# Simple Bittorrent protocol client

A simple Bittorrent protocol client that supports downloading. This is a research project to help learn about client connections, TCP, ByteStreams, multithreading, file I/O, and custom decoding.

## Description

Provide a detailed description of your project. Explain what your project is, what it does, and why it is useful. 

## Installation

### Using Docker

1. Install Docker on your machine.

2. Build the Docker image:
   docker build -t torrentclient .

3. Run a container based on the `torrentclient` image:

docker run -p 8080:8080 torrentclient <torrent-file-path> <save-path>
Replace `<torrent-file-path>` and `<save-path>` with the appropriate values for your application.

### Using JAR file

1. Install JDK 17 on your machine.

2. Run the JAR file:
java -jar TorrentClient-0.0.1-SNAPSHOT.jar <torrent-file-path> <save-path>
Replace `<torrent-file-path>` and `<save-path>` with the appropriate values for your application.

## How it Works

1. Decoding the Torrent File
* The first step is to decode the .torrent file, which is encoded in a custom format known as Bencode. [The Bencode library](https://github.com/DrBrad/Bencode/tree/main) is used for this purpose. The Torrent class is responsible for keeping torrent meta information.
* Important Fields:
  * announce: A URL for HTTP connection returning a Bencoded list of peers.
  * length: Total bytes of the entire torrent file.
  * pieceLength: Length of individual pieces.
  * pieceHashes: 20-byte arrays of hashes for each piece, for verification purposes
  * infoHash: representation of the whole torrent file.
2. Requesting Peer List
* The Peer class is responsible for creating the List of Peers to connect to for requesting files. This is achieved by establishing an HttpURLConnection with the announce URL, sending a request, and receiving back a BENCODED peer list.
* Important Points:
  * The connection is made to the announce URL obtained from the .torrent file.
  * The response received is a bencoded list of peers, which is then decoded to obtain a list of Peer objects.
  * Each Peer object contains the IP address and port number of a peer.
3. Establishing Connections with Peers
* Each connection to a peer is represented by an instance of the Client class.
* The process involves:
  * Establishing a TCP connection with the peer.
  * Creating and sending a special message called Handshake. The Handshake message is the first message sent to initiate the communication between the peer and the client. It contains:
    * Protocol identifier (to indicate the protocol being used, e.g., BitTorrent).
    * Info hash (a unique identifier for the torrent file).
    * Peer ID (a unique identifier for the client).
  * Sending a Handshake and receiving a Handshake back confirms that the peer we connected to understands the BitTorrent protocol and has the file we are requesting.
  * Receiving a Bitfield message from the peer. The Bitfield message is a binary representation of the pieces that the peer has available to share. Each bit in the Bitfield represents a piece of the file, where a '1' indicates that the peer has the piece, and a '0' indicates that the peer does not have the piece. This message is crucial as it helps the client to identify which pieces it needs to request from the peer.
4. Requesting pieces and managing connections
* After confirming that a peer has a piece we are interested in, we send an Interested message to the peer. In response, we expect to receive an Unchoke message from the peer, which changes our status to Unchoked. Only after we are Unchoked can we start sending Request messages.
* Each piece of the file, represented in the Bitfield, is divided into blocks, conventionally 16kb in size. The Request message is used to request individual blocks from the peer. After sending a Request message (or several, using pipelining), we expect to receive a Piece message from the peer. The Piece message contains the actual blocks of data we requested.
* The received blocks are saved into a buffer, and when all the blocks for a piece are received, the piece has its integrity cheched with the torrent metadata. If it passes the check and is complete - is is saved to the file using I/O operations.
* Since requesting blocks from only one client at a time is inefficient, we utilize multithreading to manage multiple client connections simultaneously. The UserClient class is responsible for managing multiple Client connections and handling synchronization issues, such as managing the work queue.
5. Merging Pieces and Completing the Download
* Once all the pieces are downloaded, they need to be merged in the correct order to reconstruct the complete file. This is the final step in the process, and after this, the downloaded file is ready for use.

## Main Challenges
1. Handling Peer Connections

* Choking and Unchoking: Peers may suddenly choke or unchoke the client. This necessitates the client to have a robust system in place to adapt its download strategy dynamically. If a peer chokes, the client needs to reassign that work to another peer if available.
* Unpredictable Behavior: Peers may also behave unpredictably, disconnecting without warning or sending corrupt data. The client must have mechanisms to detect and handle such behavior.

2. Managing Multiple Connections

* Concurrency: The client usually establishes multiple connections with different peers to download various pieces of the file concurrently. Managing these concurrent connections and ensuring that pieces are downloaded in an optimized manner is crucial.
* Synchronization: Due to the concurrent nature of downloads, synchronization issues may arise, particularly when multiple threads try to write data to the same file or read data from the work queue.
* Work Queue Failsafe: As threads pull work from a centralized queue and may get disconnected or behave unpredictably, a failsafe mechanism must be in place. This ensures that work isn't lost or stalled but instead is reassigned to another available and reliable peer.

3. Error Handling

* Connection Errors: These could occur at any stage, and the client needs to be able to recover from them efficiently without losing progress.
* Corrupt or Incomplete Pieces: It is also possible to receive corrupt or incomplete pieces from peers. A verification step is essential before committing the piece to disk.
* Piece Management Failsafe: Due to the many potential points of failure, implementing failsafes for piece management is crucial. For instance, if a piece fails verification, the client should be able to re-download it from another peer.

## Possible Improvements

While the basic functionality of the torrent client has been implemented, there are several areas where the client can be improved or expanded:

1. Improve Testing: More comprehensive testing, including unit tests, integration tests, and end-to-end tests, can help ensure the client is robust and handles all edge cases gracefully.

2. Add Seeding Support: Currently, the client only supports downloading files. Adding support for seeding, i.e., uploading files to other peers, would make the client more feature-complete and contribute to the BitTorrent ecosystem.

3. Add DHT Support: Implementing Distributed Hash Table (DHT) support would allow the client to find peers without relying on a central tracker, improving its ability to find and connect to peers.

4. Support Magnet Links: Currently, the client requires a .torrent file to initiate a download. Adding support for magnet links would make it easier to share and download torrents.

5. Add Features to Pause, Restart Manually, etc.: Implementing more user controls like pausing, restarting, or stopping the download manually would make the client more user-friendly.

6. Add a GUI: Currently, the client is command-line based. Adding a graphical user interface (GUI) would make the client more accessible to non-technical users.

7. Support Multiple File Torrents: Currently, the client supports downloading single file torrents. Adding support for torrents containing multiple files would make the client more versatile and capable of handling a broader range of torrents.

8. Optimize Download Strategy: The client can implement more advanced strategies to optimize the download speed, such as preferentially downloading rarer pieces or implementing endgame mode.


## Acknowledgments

This project uses the Bencode library by DrBrad.

