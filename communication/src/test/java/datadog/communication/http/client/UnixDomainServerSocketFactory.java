package datadog.communication.http.client;

import jnr.unixsocket.UnixServerSocketChannel;
import jnr.unixsocket.UnixSocketAddress;

import javax.net.ServerSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

final class UnixDomainServerSocketFactory extends ServerSocketFactory {
  private final Path socketPath;

  UnixDomainServerSocketFactory(Path socketPath) {
    this.socketPath = socketPath;
  }

  @Override
  public ServerSocket createServerSocket() throws IOException {
    Path socketPath1 = socketPath;
    return new ServerSocket() {
      private final Path socketPath = socketPath1;
      private UnixServerSocketChannel serverChannel;

      @Override
      public void bind(SocketAddress endpoint, int backlog) throws IOException {
        if (serverChannel != null) {
          return;
        }
        Files.deleteIfExists(socketPath);
        serverChannel = UnixServerSocketChannel.open();
        serverChannel.socket().bind(new UnixSocketAddress(socketPath.toFile()));
      }

      @Override
      public Socket accept() throws IOException {
        if (serverChannel == null) {
          throw new IOException("socket is not bound");
        }
        return serverChannel.accept().socket();
      }

      @Override
      public int getLocalPort() {
        // Dummy port value expected by MockWebServer.
        return 1;
      }

      @Override
      public synchronized void close() throws IOException {
        try {
          if (serverChannel != null) {
            serverChannel.close();
          }
        } finally {
          Files.deleteIfExists(socketPath);
        }
      }
    };
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
  public ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress)
      throws IOException {
    return createServerSocket();
  }
}
