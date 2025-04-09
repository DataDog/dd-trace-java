package datadog.smoketest;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Simple test UDP Server. Not for production use but good enough for tests */
public class TestUDPServer implements Closeable {
  public static final int DEFAULT_TIMEOUT_MS = 30 * 1000;
  public static final int DEFAULT_PACKET_SIZE = 2000;

  private static final byte[] END_MESSAGE = "END____".getBytes();

  private final BlockingQueue<String> dataPackets = new LinkedBlockingQueue<>();
  private final int timeout;
  private final int packetSize;
  private final int port;

  private volatile boolean closed = false;
  private volatile boolean closing = false;
  private DatagramSocket socket;
  private Thread readerThread;

  public TestUDPServer() {
    this(DEFAULT_TIMEOUT_MS, DEFAULT_PACKET_SIZE, 0);
  }

  public TestUDPServer(int timeout, int packetSize, int port) {
    this.timeout = timeout;
    this.packetSize = packetSize;
    this.port = port;
  }

  public synchronized void start() throws SocketException {
    if (closed) {
      throw new IllegalStateException("Server closed");
    }
    if (socket != null) {
      // already started
      return;
    }

    socket = new DatagramSocket(port);
    socket.setSoTimeout(timeout);
    readerThread =
        new Thread(
            () -> {
              while (!closed && !closing) {
                byte[] data = new byte[packetSize];
                try {
                  DatagramPacket packet = new DatagramPacket(data, packetSize);
                  socket.receive(packet);

                  byte[] trimmedData = new byte[packet.getLength()];
                  System.arraycopy(
                      packet.getData(), packet.getOffset(), trimmedData, 0, packet.getLength());

                  if (Arrays.equals(trimmedData, END_MESSAGE)) {
                    System.err.println("[TestUDPServer] Received message to close");
                    break;
                  }
                  System.err.println(
                      "[TestUDPServer] Received message: " + new String(trimmedData));
                  dataPackets.add(new String(trimmedData));
                } catch (SocketTimeoutException e) {
                  System.err.println("[TestUDPServer] Timeout waiting for message");
                  // ignore no data sent
                } catch (IOException e) {
                  System.err.println("[TestUDPServer] Error in receiving packet " + e.getMessage());
                  e.printStackTrace();
                  break;
                }
              }
              closed = true;
            },
            "Test UDP Server Receiver");

    readerThread.setDaemon(true);
    readerThread.start();
  }

  @Override
  public synchronized void close() {
    if (closed) {
      // Already closed
      return;
    }
    if (socket == null) {
      throw new IllegalStateException("Socket not open");
    }

    closing = true;

    try (DatagramSocket clientSocket = new DatagramSocket()) {
      clientSocket.send(
          new DatagramPacket(
              END_MESSAGE, END_MESSAGE.length, InetAddress.getByName("localhost"), getPort()));
    } catch (IOException e) {
      System.err.println(
          "[TestUDPServer] Exception sending close message. Will rely on socket timeout");
      e.printStackTrace();
    }

    // Closed state is set by the reader thread. Wait for it to finish
    try {
      readerThread.join();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  public int getPort() {
    if (port != 0) {
      return port;
    } else if (socket != null) {
      return socket.getLocalPort();
    } else {
      throw new IllegalStateException("Socket not open and port not explicitly set");
    }
  }

  public BlockingQueue<String> getMessages() {
    return dataPackets;
  }
}
