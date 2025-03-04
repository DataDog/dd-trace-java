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
import java.util.Iterator;

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

    System.out.println("=====start=====");

    Selector selector = Selector.open();
    System.out.println("=====1=====");
    unixSocketChannel.configureBlocking(false);
    System.out.println("=====2=====");
    unixSocketChannel.register(selector, SelectionKey.OP_READ);
    System.out.println("=====3=====");
    ByteBuffer buffer = ByteBuffer.allocate(256);

    System.out.println("=====4=====");

    try {
      while (true) {
        if (selector.select(timeout) == 0) {
          System.out.println("Timeout (" + timeout + "ms) while waiting for data.");
          break;
        }
        System.out.println("=====5=====");
        Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
        System.out.println("=====6=====");
        while (keyIterator.hasNext()) {
          System.out.println("=====7=====");
          SelectionKey key = keyIterator.next();
          System.out.println("=====8=====");
          keyIterator.remove();
          System.out.println("=====9=====");
          if (key.isReadable()) {
            System.out.println("=====10=====");
            int r = unixSocketChannel.read(buffer);
            System.out.println("=====11=====");
            if (r == -1) {
              System.out.println("=====12=====");
              unixSocketChannel.close();
              System.out.println("Not accepting client messages anymore.");
              return InputStream.nullInputStream();
            }
          }
        }
        System.out.println("=====13=====");
        buffer.flip();
        break;
      }
    } finally {
      System.out.println("=====14=====");
      selector.close();
    }

    System.out.println("=====15=====");

    return new InputStream() {
      @Override
      public int read() {
        return buffer.hasRemaining() ? (buffer.get() & 0xFF) : -1;
      }

      @Override
      public int read(byte[] bytes, int off, int len) {
        System.out.println("=====16=====");
        if (!buffer.hasRemaining()) {
          System.out.println("=====17=====");
          return -1;
        }
        len = Math.min(len, buffer.remaining());
        System.out.println("=====18=====");
        buffer.get(bytes, off, len);
        System.out.println("=====19=====");
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
