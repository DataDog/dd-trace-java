package datadog.common.socket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * Subtype UNIX socket for a higher-fidelity impersonation of TCP sockets. This is named "tunneling"
 * because it assumes the ultimate destination has a hostname and port.
 *
 * <p>Bsed on {@link TunnelingUnixSocket}; adapted to use the built-in UDS support added in Java 16.
 */
final class TunnelingJdkSocket extends Socket {
  private final SocketAddress unixSocketAddress;
  private InetSocketAddress inetSocketAddress;

  private SocketChannel unixSocketChannel;

  private int timeout;
  private boolean shutIn;
  private boolean shutOut;
  private boolean closed;

  TunnelingJdkSocket(final Path path) {
    this.unixSocketAddress = UnixDomainSocketAddress.of(path);
  }

  TunnelingJdkSocket(final Path path, final InetSocketAddress address) {
    this(path);
    inetSocketAddress = address;
  }

  @Override
  public boolean isConnected() {
    return null != unixSocketChannel;
  }

  @Override
  public boolean isInputShutdown() {
    return shutIn;
  }

  @Override
  public boolean isOutputShutdown() {
    return shutOut;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public synchronized void setSoTimeout(int timeout) throws SocketException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (timeout < 0) {
      throw new IllegalArgumentException("Socket timeout can't be negative");
    }
    this.timeout = timeout;
  }

  @Override
  public synchronized int getSoTimeout() throws SocketException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    return timeout;
  }

  @Override
  public void connect(final SocketAddress endpoint) throws IOException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (isConnected()) {
      throw new SocketException("Socket is already connected");
    }
    inetSocketAddress = (InetSocketAddress) endpoint;
    unixSocketChannel = SocketChannel.open(unixSocketAddress);
  }

  @Override
  public void connect(final SocketAddress endpoint, final int timeout) throws IOException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (isConnected()) {
      throw new SocketException("Socket is already connected");
    }
    inetSocketAddress = (InetSocketAddress) endpoint;
    unixSocketChannel = SocketChannel.open(unixSocketAddress);
  }

  @Override
  public SocketChannel getChannel() {
    return unixSocketChannel;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (!isConnected()) {
      throw new SocketException("Socket is not connected");
    }
    if (isInputShutdown()) {
      throw new SocketException("Socket input is shutdown");
    }

    Selector selector = Selector.open();
    unixSocketChannel.configureBlocking(false);
    unixSocketChannel.register(selector, SelectionKey.OP_READ);
    ByteBuffer buffer = ByteBuffer.allocate(256); // arbitrary buffer size for now

    try {
      if (selector.select(timeout) == 0) {
        System.out.println("Timeout (" + timeout + "ms) while waiting for data.");
      }
      for (SelectionKey key : selector.selectedKeys()) {
        if (key.isReadable()) {
          int r = unixSocketChannel.read(buffer);
          if (r == -1) {
            unixSocketChannel.close();
            System.out.println("Not accepting client messages anymore.");
          }
        }
      }
      buffer.flip();
    } finally {
      selector.close();
    }

    return new InputStream() {
      @Override
      public int read() {
        return buffer.hasRemaining() ? (buffer.get() & 0xFF) : -1;
      }

      @Override
      public int read(byte[] bytes, int off, int len) {
        if (!buffer.hasRemaining()) {
          return -1;
        }
        len = Math.min(len, buffer.remaining());
        buffer.get(bytes, off, len);
        return len;
      }
    };
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (!isConnected()) {
      throw new SocketException("Socket is not connected");
    }
    if (isInputShutdown()) {
      throw new SocketException("Socket output is shutdown");
    }
    return Channels.newOutputStream(unixSocketChannel);
  }

  @Override
  public void shutdownInput() throws IOException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (!isConnected()) {
      throw new SocketException("Socket is not connected");
    }
    if (isInputShutdown()) {
      throw new SocketException("Socket input is already shutdown");
    }
    unixSocketChannel.shutdownInput();
    shutIn = true;
  }

  @Override
  public void shutdownOutput() throws IOException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (!isConnected()) {
      throw new SocketException("Socket is not connected");
    }
    if (isOutputShutdown()) {
      throw new SocketException("Socket output is already shutdown");
    }
    unixSocketChannel.shutdownOutput();
    shutOut = true;
  }

  @Override
  public InetAddress getInetAddress() {
    return inetSocketAddress.getAddress();
  }

  @Override
  public void close() throws IOException {
    if (isClosed()) {
      return;
    }
    if (null != unixSocketChannel) {
      unixSocketChannel.close();
    }
    closed = true;
  }
}
