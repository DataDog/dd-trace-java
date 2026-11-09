package datadog.common.socket;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.SocketFactory;

public class NamedPipeSocketFactory extends SocketFactory {
  private static final String NAMED_PIPE_PREFIX = "\\\\.\\pipe\\";

  /** The .NET tracer's default named pipe connect timeout, {@code DD_TRACE_PIPE_TIMEOUT_MS}. */
  static final long CONNECT_TIMEOUT_MILLIS = 500;

  private final File pipe;

  public NamedPipeSocketFactory(String pipeName) {
    String pipeNameWithPrefix =
        pipeName.startsWith(NAMED_PIPE_PREFIX) ? pipeName : NAMED_PIPE_PREFIX + pipeName;
    this.pipe = new File(pipeNameWithPrefix);
  }

  NamedPipeSocketFactory(File pipe) {
    this.pipe = pipe;
  }

  @Override
  public Socket createSocket() throws IOException {
    // Each connection gets its own pipe handle. A shared handle lets a blocked read on one
    // connection, such as a pooled-connection health check, block every other request.
    return openPipe();
  }

  /**
   * Opens the pipe, retrying for up to {@link #CONNECT_TIMEOUT_MILLIS} like .NET's {@code
   * NamedPipeClientStream.Connect}. A pipe server creates its next pipe instance only after a
   * client connects, so a client that opens the pipe in between fails with "All pipe instances are
   * busy". Java reports that, a missing pipe, and every other failure to open the pipe as {@link
   * FileNotFoundException}, so all of them are retried.
   */
  private NamedPipeSocket openPipe() throws IOException {
    long deadlineNanos = System.nanoTime() + MILLISECONDS.toNanos(CONNECT_TIMEOUT_MILLIS);
    while (true) {
      try {
        return new NamedPipeSocket(pipe);
      } catch (FileNotFoundException e) {
        if (System.nanoTime() - deadlineNanos >= 0) {
          throw e;
        }
        try {
          Thread.sleep(1);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw e;
        }
      }
    }
  }

  @Override
  public Socket createSocket(String host, int port) throws IOException {
    return createSocket();
  }

  @Override
  public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
      throws IOException {
    return createSocket();
  }

  @Override
  public Socket createSocket(InetAddress host, int port) throws IOException {
    return createSocket();
  }

  @Override
  public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
      throws IOException {
    return createSocket();
  }
}
