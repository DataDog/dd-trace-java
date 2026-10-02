package datadog.common.socket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Real UDS connection setup and bulk I/O on reused connections. Requires JDK 17+ on Linux or macOS.
 *
 * <p>Run {@code :utils:socket-utils:jmh -PtestJvm=17 -Pjmh.profilers=gc}. Compare revisions on the
 * same JVM and host. Round-trip time includes the peer thread and OS scheduling; GC results include
 * peer allocations. This does not measure HTTP encoding or timeout/backpressure recovery.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class TunnelingJdkSocketBenchmark {
  private static final InetSocketAddress ENDPOINT = new InetSocketAddress("localhost", 0);

  @Benchmark
  public void connectAndClose(Listener listener, Blackhole blackhole) throws IOException {
    try (TunnelingJdkSocket socket = new TunnelingJdkSocket(listener.path)) {
      socket.connect(ENDPOINT);
      try (SocketChannel peer = listener.server.accept()) {
        blackhole.consume(socket.getInputStream());
        blackhole.consume(socket.getOutputStream());
      }
    }
  }

  @Benchmark
  public byte roundTrip(Connection connection) throws IOException {
    connection.output.write(connection.request);
    int received = 0;
    while (received < connection.response.length) {
      int count =
          connection.input.read(
              connection.response, received, connection.response.length - received);
      if (count <= 0) {
        throw new IOException("Peer closed or timed out", connection.peerFailure);
      }
      received += count;
    }
    return connection.response[received - 1];
  }

  @State(Scope.Thread)
  public static class Listener {
    private Path path;
    private ServerSocketChannel server;

    @Setup
    public void setup() throws IOException {
      // Gradle's JMH temp directory can exceed the Unix-domain socket path limit.
      path = Files.createTempFile(Paths.get("/tmp"), "uds-jmh-", ".sock");
      Files.delete(path);
      server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
      try {
        server.bind(UnixDomainSocketAddress.of(path));
      } catch (IOException | RuntimeException e) {
        close();
        throw e;
      }
    }

    @TearDown
    public void close() throws IOException {
      try {
        server.close();
      } finally {
        Files.deleteIfExists(path);
      }
    }
  }

  @State(Scope.Thread)
  public static class Connection {
    @Param({"256", "8192", "65536"})
    public int bytes;

    private Socket socket;
    private SocketChannel peer;
    private InputStream input;
    private OutputStream output;
    private byte[] request;
    private byte[] response;
    private Thread responder;
    private volatile IOException peerFailure;

    @Setup
    public void setup(Listener listener) throws IOException {
      request = new byte[bytes];
      Arrays.fill(request, (byte) 1);
      response = new byte[bytes];
      socket = new TunnelingJdkSocket(listener.path);
      try {
        socket.connect(ENDPOINT);
        peer = listener.server.accept();
        socket.setSoTimeout(5000);
        input = socket.getInputStream();
        output = socket.getOutputStream();
      } catch (IOException | RuntimeException e) {
        socket.close();
        if (peer != null) {
          peer.close();
        }
        throw e;
      }
      responder = new Thread(this::echo, "uds-jmh-peer");
      responder.setDaemon(true);
      responder.start();
    }

    private void echo() {
      ByteBuffer buffer = ByteBuffer.allocate(bytes);
      try {
        while (true) {
          buffer.clear();
          while (buffer.hasRemaining()) {
            if (peer.read(buffer) == -1) {
              return;
            }
          }
          buffer.flip();
          while (buffer.hasRemaining()) {
            peer.write(buffer);
          }
        }
      } catch (IOException e) {
        if (peer.isOpen()) {
          peerFailure = e;
          try {
            socket.close();
          } catch (IOException closeFailure) {
            e.addSuppressed(closeFailure);
          }
        }
      }
    }

    @TearDown
    public void close() throws IOException, InterruptedException {
      try {
        peer.close();
      } finally {
        socket.close();
      }
      responder.join(TimeUnit.SECONDS.toMillis(5));
      if (responder.isAlive()) {
        throw new IllegalStateException("Peer did not terminate");
      }
      if (peerFailure != null) {
        throw peerFailure;
      }
    }
  }
}
