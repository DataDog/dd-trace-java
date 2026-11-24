package datadog.common.socket;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.opentest4j.TestAbortedException;

/** A Unix domain socket server that echoes the bytes of each connection, one at a time. */
final class UnixSocketEchoServer implements AutoCloseable {
  private final Path path;
  private final ServerSocketChannel serverChannel;
  private final Thread acceptor;

  UnixSocketEchoServer(Path path) throws IOException {
    this.path = path;
    try {
      this.serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    } catch (UnsupportedOperationException e) {
      throw new TestAbortedException("Unix domain sockets are not supported", e);
    }
    this.serverChannel.bind(UnixDomainSocketAddress.of(path));
    this.acceptor = new Thread(this::echoConnections, "unix-socket-echo-server");
    this.acceptor.setDaemon(true);
    this.acceptor.start();
  }

  Path path() {
    return this.path;
  }

  private void echoConnections() {
    ByteBuffer buffer = ByteBuffer.allocate(1024);
    while (this.serverChannel.isOpen()) {
      try (SocketChannel client = this.serverChannel.accept()) {
        while (client.read(buffer) > 0) {
          buffer.flip();
          while (buffer.hasRemaining()) {
            client.write(buffer);
          }
          buffer.clear();
        }
      } catch (IOException e) {
        // the server was closed, or the client went away
      }
    }
  }

  @Override
  public void close() throws Exception {
    this.serverChannel.close();
    this.acceptor.join(TimeUnit.SECONDS.toMillis(5));
    Files.deleteIfExists(this.path);
  }
}
