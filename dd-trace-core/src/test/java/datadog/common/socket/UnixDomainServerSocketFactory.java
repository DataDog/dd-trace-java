package datadog.common.socket;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.channels.ClosedChannelException;
import javax.net.ServerSocketFactory;
import jnr.unixsocket.UnixServerSocketChannel;
import jnr.unixsocket.UnixSocketAddress;
import jnr.unixsocket.UnixSocketChannel;

/**
 * Adapts a JNR Unix-domain server channel to APIs such as MockWebServer that require a {@link
 * ServerSocket}. Adapted from OkHttp's <a
 * href="https://github.com/square/okhttp/blob/master/samples/unixdomainsockets/src/main/java/okhttp3/unixdomainsockets/UnixDomainServerSocketFactory.java">Unix-domain
 * socket sample</a>.
 */
public final class UnixDomainServerSocketFactory extends ServerSocketFactory {
  private final File path;

  public UnixDomainServerSocketFactory(File path) {
    this.path = path;
  }

  @Override
  public ServerSocket createServerSocket() throws IOException {
    return new UnixDomainServerSocket();
  }

  @Override
  public ServerSocket createServerSocket(int port) throws IOException {
    return createServerSocket();
  }

  @Override
  public ServerSocket createServerSocket(int port, int backlog) throws IOException {
    return createServerSocket();
  }

  @Override
  public ServerSocket createServerSocket(int port, int backlog, InetAddress inetAddress)
      throws IOException {
    return createServerSocket();
  }

  private final class UnixDomainServerSocket extends ServerSocket {
    private UnixServerSocketChannel serverSocketChannel;
    private InetSocketAddress endpoint;

    private UnixDomainServerSocket() throws IOException {}

    @Override
    public void bind(SocketAddress endpoint, int backlog) throws IOException {
      this.endpoint = (InetSocketAddress) endpoint;
      UnixServerSocketChannel channel = UnixServerSocketChannel.open();
      boolean bound = false;
      try {
        channel.configureBlocking(true);
        channel.socket().bind(new UnixSocketAddress(path));
        serverSocketChannel = channel;
        bound = true;
      } finally {
        if (!bound) {
          channel.close();
        }
      }
    }

    @Override
    public void setReuseAddress(boolean on) {
      // MockWebServer configures this TCP option before binding. It has no UDS equivalent.
    }

    @Override
    public int getLocalPort() {
      return 1; // MockWebServer requires a port even though a UDS has none.
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
      return endpoint;
    }

    @Override
    public Socket accept() throws IOException {
      try {
        UnixSocketChannel channel = serverSocketChannel.accept();
        return new TunnelingUnixSocket(path, channel, endpoint);
      } catch (ClosedChannelException e) {
        SocketException socketException = new SocketException("Socket is closed");
        socketException.initCause(e);
        throw socketException;
      }
    }

    @Override
    public void close() throws IOException {
      super.close();
      if (serverSocketChannel != null) {
        serverSocketChannel.close();
      }
    }
  }
}
