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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Set;

/**
 * Subtype UNIX socket for a higher-fidelity impersonation of TCP sockets. This is named "tunneling"
 * because it assumes the ultimate destination has a hostname and port.
 *
 * <p>Based on {@link TunnelingUnixSocket}; adapted to use the built-in UDS support added in Java
 * 16.
 */
final class TunnelingJdkSocket extends Socket {
  private final SocketAddress unixSocketAddress;
  private InetSocketAddress inetSocketAddress;

  private SocketChannel unixSocketChannel;
  private Selector selector;

  private int timeout;
  private boolean shutIn;
  private boolean shutOut;
  private boolean closed;

  protected static final int DEFAULT_BUFFER_SIZE = 8192;
  // Indicate that the buffer size is not set by initializing to -1
  private int sendBufferSize = -1;
  private int receiveBufferSize = -1;

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
    if (endpoint == null) {
      throw new IllegalArgumentException("Endpoint cannot be null");
    }
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (isConnected()) {
      throw new SocketException("Socket is already connected");
    }
    inetSocketAddress = (InetSocketAddress) endpoint;
    unixSocketChannel = SocketChannel.open(unixSocketAddress);
  }

  // `timeout` is intentionally ignored here, like in the jnr-unixsocket implementation.
  // See:
  // https://github.com/jnr/jnr-unixsocket/blob/master/src/main/java/jnr/unixsocket/UnixSocket.java#L89-L97
  @Override
  public void connect(final SocketAddress endpoint, final int timeout) throws IOException {
    if (endpoint == null) {
      throw new IllegalArgumentException("Endpoint cannot be null");
    }
    if (timeout < 0) {
      throw new IllegalArgumentException("Timeout cannot be negative");
    }
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
  public void setSendBufferSize(int size) throws SocketException {
    if (size <= 0) {
      throw new IllegalArgumentException("Invalid send buffer size");
    }
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    sendBufferSize = size;
    try {
      unixSocketChannel.setOption(java.net.StandardSocketOptions.SO_SNDBUF, size);
    } catch (IOException e) {
      SocketException se = new SocketException("Failed to set send buffer size socket option");
      se.initCause(e);
      throw se;
    }
  }

  @Override
  public int getSendBufferSize() throws SocketException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (sendBufferSize == -1) {
      return DEFAULT_BUFFER_SIZE;
    }
    return sendBufferSize;
  }

  @Override
  public void setReceiveBufferSize(int size) throws SocketException {
    if (size <= 0) {
      throw new IllegalArgumentException("Invalid receive buffer size");
    }
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    receiveBufferSize = size;
    try {
      unixSocketChannel.setOption(java.net.StandardSocketOptions.SO_RCVBUF, size);
    } catch (IOException e) {
      SocketException se = new SocketException("Failed to set receive buffer size socket option");
      se.initCause(e);
      throw se;
    }
  }

  @Override
  public int getReceiveBufferSize() throws SocketException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (receiveBufferSize == -1) {
      return DEFAULT_BUFFER_SIZE;
    }
    return receiveBufferSize;
  }

  public int getStreamBufferSize() throws SocketException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (sendBufferSize == -1 && receiveBufferSize == -1) {
      return DEFAULT_BUFFER_SIZE;
    }
    return Math.max(sendBufferSize, receiveBufferSize);
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

    if (selector == null) {
      selector = Selector.open();
      unixSocketChannel.configureBlocking(false);
      unixSocketChannel.register(selector, SelectionKey.OP_READ);
    }

    return new InputStream() {
      private final ByteBuffer buffer = ByteBuffer.allocate(getStreamBufferSize());

      @Override
      public int read() throws IOException {
        byte[] nextByte = new byte[1];
        return (read(nextByte, 0, 1) == -1) ? -1 : (nextByte[0] & 0xFF);
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        if (isInputShutdown()) {
          return -1;
        }
        buffer.clear();

        int readyChannels = selector.select(timeout);
        if (readyChannels == 0) {
          return 0;
        }

        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        synchronized (selectedKeys) {
          Iterator<SelectionKey> keyIterator = selectedKeys.iterator();
          while (keyIterator.hasNext()) {
            SelectionKey key = keyIterator.next();
            keyIterator.remove();
            if (key.isReadable()) {
              int r = unixSocketChannel.read(buffer);
              if (r == -1) {
                return -1;
              }
              buffer.flip();
              len = Math.min(r, len);
              buffer.get(b, off, len);
              return len;
            }
          }
        }
        return 0;
      }

      @Override
      public void close() throws IOException {
        TunnelingJdkSocket.this.close();
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
    if (isOutputShutdown()) {
      throw new SocketException("Socket output is shutdown");
    }

    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        byte[] array = ByteBuffer.allocate(4).putInt(b).array();
        write(array, 0, 4);
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        if (isOutputShutdown()) {
          throw new IOException("Stream closed");
        }
        ByteBuffer buffer = ByteBuffer.wrap(b, off, len);
        while (buffer.hasRemaining()) {
          unixSocketChannel.write(buffer);
        }
      }

      @Override
      public void close() throws IOException {
        TunnelingJdkSocket.this.close();
      }
    };
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
    if (!isConnected()) {
      return null;
    }
    return inetSocketAddress.getAddress();
  }

  @Override
  public void close() throws IOException {
    if (isClosed()) {
      return;
    }
    // Ignore possible exceptions so that we continue closing the socket
    try {
      if (!isInputShutdown()) {
        shutdownInput();
      }
    } catch (IOException e) {
    }
    try {
      if (!isOutputShutdown()) {
        shutdownOutput();
      }
    } catch (IOException e) {
    }
    try {
      if (selector != null) {
        selector.close();
        selector = null;
      }
    } catch (IOException e) {
    }
    try {
      if (unixSocketChannel != null) {
        unixSocketChannel.close();
      }
    } catch (IOException e) {
    }
    closed = true;
  }
}
