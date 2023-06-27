package datadog.trace.civisibility.ipc;

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
  public SignalClient(InetSocketAddress serverAddress) throws IOException {
    this(serverAddress, DEFAULT_SOCKET_TIMEOUT_MILLIS);
  }

  @SuppressForbidden
  SignalClient(InetSocketAddress serverAddress, int socketTimeoutMillis) throws IOException {
    if (serverAddress == null) {
      throw new IOException("Cannot open connection to signal server: no address specified");
    }

    socketChannel = SocketChannel.open();
    Socket socket = socketChannel.socket();
    socket.setSoTimeout(socketTimeoutMillis);
    socket.connect(serverAddress, socketTimeoutMillis);
  }

  @Override
  public void close() throws IOException {
    socketChannel.close();
  }

  public void send(Signal signal) throws IOException {
    ByteBuffer message = signal.serialize();
    if (message.remaining() > 0xFFFF) {
      throw new IllegalArgumentException("Message too long: " + message.remaining());
    }

    ByteBuffer length = ByteBuffer.allocate(2);
    length.putShort((short) message.remaining());
    length.flip();
    socketChannel.write(length);
    socketChannel.write(message);

    // reading is done this way to make socket channel respect timeout
    Socket socket = socketChannel.socket();
    InputStream inputStream = socket.getInputStream();
    int ignored = inputStream.read(); // waiting for the ack byte
  }
}
