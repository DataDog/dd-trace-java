package datadog.common.socket;

import datadog.trace.api.internal.VisibleForTesting;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
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
  private final UnixDomainSocketAddress unixSocketAddress;
  private final SocketChannel unixSocketChannel;

  private volatile InetSocketAddress inetSocketAddress;
  @VisibleForTesting volatile Selector selector;

  private volatile int timeout;
  private volatile boolean shutIn;
  private volatile boolean shutOut;
  private volatile boolean closed;

  static final int DEFAULT_BUFFER_SIZE = 8192;
  // Indicate that the buffer size is not set by initializing to -1
  private int sendBufferSize = -1;
  private int receiveBufferSize = -1;

  TunnelingJdkSocket(final Path path) throws IOException, UnsupportedOperationException {
    this.unixSocketAddress = UnixDomainSocketAddress.of(path);
    this.unixSocketChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
  }

  @Override
  public boolean isConnected() {
    return inetSocketAddress != null;
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
  public void setSoTimeout(int timeout) throws SocketException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    if (timeout < 0) {
      throw new IllegalArgumentException("Socket timeout can't be negative");
    }
    this.timeout = timeout;
  }

  @Override
  public int getSoTimeout() throws SocketException {
    if (isClosed()) {
      throw new SocketException("Socket is closed");
    }
    return timeout;
  }

  @Override
  public void connect(final SocketAddress endpoint) throws IOException {
    connect(endpoint, 0);
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
    InetSocketAddress inetSocketAddress = (InetSocketAddress) endpoint;
    try {
      unixSocketChannel.connect(unixSocketAddress);
      this.inetSocketAddress = inetSocketAddress;
    } catch (IOException e) {
      close();
      throw e;
    }
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
    // Serialize validation and selector publication with close() so close cannot miss a selector
    // that is still being initialized.
    synchronized (this) {
      if (isClosed()) {
        throw new SocketException("Socket is closed");
      }
      if (!isConnected()) {
        throw new SocketException("Socket is not connected");
      }
      if (isInputShutdown()) {
        throw new SocketException("Socket input is shutdown");
      }

      Selector currentSelector = selector;
      if (currentSelector == null) {
        currentSelector = Selector.open();
        try {
          unixSocketChannel.configureBlocking(false);
          unixSocketChannel.register(currentSelector, SelectionKey.OP_READ);
          selector = currentSelector;
        } catch (IOException | RuntimeException e) {
          try {
            currentSelector.close();
          } catch (IOException closeException) {
            e.addSuppressed(closeException);
          }
          throw e;
        }
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

          Selector currentSelector = selector;
          if (currentSelector == null) {
            throw new SocketException("Socket is closed");
          }

          try {
            int readyChannels = currentSelector.select(timeout);
            if (readyChannels == 0) {
              if (isClosed() || !currentSelector.isOpen()) {
                throw new SocketException("Socket is closed");
              }
              return 0;
            }

            Set<SelectionKey> selectedKeys = currentSelector.selectedKeys();
            // Multiple input streams share this selector, so serialize iteration and removal from
            // its non-thread-safe selected-key set.
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
          } catch (ClosedSelectorException | CancelledKeyException e) {
            SocketException socketException = new SocketException("Socket is closed");
            socketException.initCause(e);
            throw socketException;
          }
        }

        @Override
        public void close() throws IOException {
          TunnelingJdkSocket.this.close();
        }
      };
    }
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
    // Keep validation, channel shutdown, and state publication atomic with close().
    synchronized (this) {
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
  }

  @Override
  public void shutdownOutput() throws IOException {
    // Keep validation, channel shutdown, and state publication atomic with close().
    synchronized (this) {
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
  }

  @Override
  public InetAddress getInetAddress() {
    if (!isConnected()) {
      return null;
    }
    return inetSocketAddress.getAddress();
  }

  @Override
  public void close() {
    Selector currentSelector;
    // Publish the terminal state and snapshot the selector atomically with selector creation and
    // half-close operations. The resources are closed after releasing this monitor.
    synchronized (this) {
      if (isClosed()) {
        return;
      }
      shutIn = true;
      shutOut = true;
      closed = true;
      currentSelector = selector;
    }
    // Ignore possible exceptions so that we continue closing the socket
    try {
      if (currentSelector != null) {
        currentSelector.close();
      }
    } catch (IOException ignored) {
    }
    try {
      unixSocketChannel.close();
    } catch (IOException ignored) {
    }
  }
}
