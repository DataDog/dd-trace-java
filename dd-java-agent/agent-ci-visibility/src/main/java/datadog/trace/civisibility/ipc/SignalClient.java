package datadog.trace.civisibility.ipc;

import static datadog.trace.civisibility.ipc.SignalServer.HOST_PORT_SEPARATOR;

import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class SignalClient implements AutoCloseable {

  private static final int DEFAULT_SOCKET_TIMEOUT_MILLIS = 10_000;

  private final SocketChannel socketChannel;

  @SuppressForbidden
  public SignalClient(String serverAddress) throws IOException {
    this(serverAddress, DEFAULT_SOCKET_TIMEOUT_MILLIS);
  }

  @SuppressForbidden
  SignalClient(String serverAddress, int socketTimeoutMillis) throws IOException {
    if (serverAddress == null) {
      throw new IOException("Cannot open connection to signal server: no address specified");
    }

    String[] tokens = serverAddress.split(HOST_PORT_SEPARATOR);
    if (tokens.length != 2) {
      throw new IOException(
          "Cannot open connection to signal server: unexpected address string format: "
              + serverAddress);
    }

    String host = tokens[0];
    int port = Integer.parseInt(tokens[1]);
    InetSocketAddress socketAddress = new InetSocketAddress(host, port);

    socketChannel = SocketChannel.open();

    Socket socket = socketChannel.socket();
    socket.setSoTimeout(socketTimeoutMillis);
    socket.connect(socketAddress, socketTimeoutMillis);
  }

  @Override
  public void close() throws IOException {
    socketChannel.close();
  }

  public void send(Signal signal) throws IOException {
    byte[] message = signal.serialize();
    if (message.length > 0xFFFF) {
      throw new IllegalArgumentException("Message too long: " + message.length);
    }

    byte[] length = new byte[2];
    ByteUtils.putShort(length, 0, (short) message.length);
    socketChannel.write(ByteBuffer.wrap(length));
    socketChannel.write(ByteBuffer.wrap(message));

    // reading is done this way to make socket channel respect timeout
    Socket socket = socketChannel.socket();
    InputStream inputStream = socket.getInputStream();
    int ignored = inputStream.read(); // waiting for the ack byte
  }
}
