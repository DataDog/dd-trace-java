package datadog.common.socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.condition.JRE.JAVA_16;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;

@EnabledForJreRange(min = JAVA_16)
public class TunnelingJdkSocketTest {

  private TestUnixSocketServer server;

  @BeforeAll
  static void assumeUnixDomainSocketsAreSupported() {
    Assumptions.assumeTrue(udsSupported());
  }

  @AfterEach
  void closeServer() throws Exception {
    if (server != null) {
      server.close();
      server = null;
    }
  }

  @Test
  public void testSocketConnectAndClose() throws Exception {
    Path socketPath = getSocketPath();
    UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);
    startServer(socketAddress);
    TunnelingJdkSocket clientSocket = new TunnelingJdkSocket(socketPath);

    assertFalse(clientSocket.isConnected());
    assertFalse(clientSocket.isClosed());

    clientSocket.connect(new InetSocketAddress("localhost", 0));
    InputStream inputStream = clientSocket.getInputStream();
    OutputStream outputStream = clientSocket.getOutputStream();

    assertTrue(clientSocket.isConnected());
    assertFalse(clientSocket.isClosed());
    assertFalse(clientSocket.isInputShutdown());
    assertFalse(clientSocket.isOutputShutdown());
    assertThrows(
        SocketException.class, () -> clientSocket.connect(new InetSocketAddress("localhost", 0)));

    clientSocket.close();

    assertTrue(clientSocket.isConnected());
    assertTrue(clientSocket.isClosed());
    assertTrue(clientSocket.isInputShutdown());
    assertTrue(clientSocket.isOutputShutdown());
    assertEquals(-1, inputStream.read());
    assertThrows(IOException.class, () -> outputStream.write(1));
    assertThrows(SocketException.class, clientSocket::getInputStream);
    assertThrows(SocketException.class, clientSocket::getOutputStream);
    clientSocket.close();
  }

  @Test
  public void testInputStreamClose() throws Exception {
    TunnelingJdkSocket clientSocket = createClient();
    InputStream inputStream = clientSocket.getInputStream();
    OutputStream outputStream = clientSocket.getOutputStream();

    assertFalse(clientSocket.isClosed());
    assertFalse(clientSocket.isInputShutdown());
    assertFalse(clientSocket.isOutputShutdown());

    inputStream.close();

    assertTrue(clientSocket.isClosed());
    assertTrue(clientSocket.isInputShutdown());
    assertTrue(clientSocket.isOutputShutdown());
    assertEquals(-1, inputStream.read());
    assertThrows(IOException.class, () -> outputStream.write(1));
    assertThrows(SocketException.class, clientSocket::getInputStream);
    assertThrows(SocketException.class, clientSocket::getOutputStream);
  }

  @Test
  public void testOutputStreamClose() throws Exception {
    TunnelingJdkSocket clientSocket = createClient();
    InputStream inputStream = clientSocket.getInputStream();
    OutputStream outputStream = clientSocket.getOutputStream();

    assertFalse(clientSocket.isClosed());
    assertFalse(clientSocket.isInputShutdown());
    assertFalse(clientSocket.isOutputShutdown());

    outputStream.close();

    assertTrue(clientSocket.isClosed());
    assertTrue(clientSocket.isInputShutdown());
    assertTrue(clientSocket.isOutputShutdown());
    assertEquals(-1, inputStream.read());
    assertThrows(IOException.class, () -> outputStream.write(1));
    assertThrows(SocketException.class, clientSocket::getInputStream);
    assertThrows(SocketException.class, clientSocket::getOutputStream);
  }

  @Test
  public void testTimeout() throws Exception {
    TunnelingJdkSocket clientSocket = createClient();
    InputStream inputStream = clientSocket.getInputStream();

    int testTimeout = 1000;
    clientSocket.setSoTimeout(testTimeout);
    assertEquals(testTimeout, clientSocket.getSoTimeout());

    long startTime = System.currentTimeMillis();
    int readResult = inputStream.read();
    long endTime = System.currentTimeMillis();
    long readDuration = endTime - startTime;
    int timeVariance = 100;
    assertTrue(readDuration >= testTimeout && readDuration <= testTimeout + timeVariance);
    assertEquals(0, readResult);

    int newTimeout = testTimeout / 2;
    clientSocket.setSoTimeout(newTimeout);
    assertEquals(newTimeout, clientSocket.getSoTimeout());
    assertTimeoutPreemptively(Duration.ofMillis(testTimeout), () -> inputStream.read());

    // The socket should block indefinitely when timeout is set to 0, per
    // https://docs.oracle.com/en/java/javase/16/docs/api//java.base/java/net/Socket.html#setSoTimeout(int).
    int infiniteTimeout = 0;
    clientSocket.setSoTimeout(infiniteTimeout);
    assertEquals(infiniteTimeout, clientSocket.getSoTimeout());
    try {
      assertTimeoutPreemptively(Duration.ofMillis(testTimeout), () -> inputStream.read());
      fail("Read should block indefinitely with infinite timeout");
    } catch (AssertionError e) {
      // Expected
    }

    int invalidTimeout = -1;
    assertThrows(IllegalArgumentException.class, () -> clientSocket.setSoTimeout(invalidTimeout));

    clientSocket.close();
    assertThrows(SocketException.class, () -> clientSocket.setSoTimeout(testTimeout));
    assertThrows(SocketException.class, clientSocket::getSoTimeout);
  }

  @Test
  public void testBufferSizes() throws Exception {
    TunnelingJdkSocket clientSocket = createClient();

    assertEquals(TunnelingJdkSocket.DEFAULT_BUFFER_SIZE, clientSocket.getSendBufferSize());
    assertEquals(TunnelingJdkSocket.DEFAULT_BUFFER_SIZE, clientSocket.getReceiveBufferSize());
    assertEquals(TunnelingJdkSocket.DEFAULT_BUFFER_SIZE, clientSocket.getStreamBufferSize());

    int newBufferSize = TunnelingJdkSocket.DEFAULT_BUFFER_SIZE / 2;
    clientSocket.setSendBufferSize(newBufferSize);
    clientSocket.setReceiveBufferSize(newBufferSize / 2);
    assertEquals(newBufferSize, clientSocket.getSendBufferSize());
    assertEquals(newBufferSize / 2, clientSocket.getReceiveBufferSize());
    assertEquals(newBufferSize, clientSocket.getStreamBufferSize());

    int invalidBufferSize = -1;
    assertThrows(
        IllegalArgumentException.class, () -> clientSocket.setSendBufferSize(invalidBufferSize));
    assertThrows(
        IllegalArgumentException.class, () -> clientSocket.setReceiveBufferSize(invalidBufferSize));

    clientSocket.close();
    assertThrows(
        SocketException.class,
        () -> clientSocket.setSendBufferSize(TunnelingJdkSocket.DEFAULT_BUFFER_SIZE));
    assertThrows(
        SocketException.class,
        () -> clientSocket.setReceiveBufferSize(TunnelingJdkSocket.DEFAULT_BUFFER_SIZE));
    assertThrows(SocketException.class, clientSocket::getSendBufferSize);
    assertThrows(SocketException.class, clientSocket::getReceiveBufferSize);
    assertThrows(SocketException.class, clientSocket::getStreamBufferSize);
  }

  @Test
  public void testFileDescriptorLeak() throws Exception {
    long initialCount = getFileDescriptorCount();

    TunnelingJdkSocket clientSocket = createClient();

    for (int i = 0; i < 100; i++) {
      InputStream inputStream = clientSocket.getInputStream();
      long currentCount = getFileDescriptorCount();
      assertTrue(currentCount <= initialCount + 7);
    }

    clientSocket.close();
    closeServer();

    long finalCount = getFileDescriptorCount();
    assertTrue(finalCount <= initialCount + 3);
  }

  @Test
  public void testClosedSelectorIsReportedAsSocketException() throws Exception {
    try (TunnelingJdkSocket clientSocket = createClient()) {
      InputStream inputStream = clientSocket.getInputStream();
      clientSocket.selector.close();

      SocketException exception = assertThrows(SocketException.class, inputStream::read);

      assertInstanceOf(ClosedSelectorException.class, exception.getCause());
    }
  }

  @Test
  public void testSelectorClosedBetweenSelectAndSelectedKeysIsReportedAsSocketException()
      throws Exception {
    try (TunnelingJdkSocket clientSocket = createClient()) {
      InputStream inputStream = clientSocket.getInputStream();
      clientSocket.selector.close();
      clientSocket.selector = new ClosedAfterSelectSelector();

      SocketException exception = assertThrows(SocketException.class, inputStream::read);

      assertInstanceOf(ClosedSelectorException.class, exception.getCause());
    }
  }

  @Test
  public void testCancelledKeyIsReportedAsSocketException() throws Exception {
    try (TunnelingJdkSocket clientSocket = createClient()) {
      InputStream inputStream = clientSocket.getInputStream();
      clientSocket.selector.close();
      clientSocket.selector = new CancelledKeySelector();

      SocketException exception = assertThrows(SocketException.class, inputStream::read);

      assertInstanceOf(CancelledKeyException.class, exception.getCause());
    }
  }

  @Test
  public void testAsynchronousCloseInterruptsBlockedReadWithIOException() throws Exception {
    TunnelingJdkSocket clientSocket = createClient();
    Thread reader = null;
    try {
      InputStream inputStream = clientSocket.getInputStream();
      clientSocket.selector.close();
      BlockingCloseSelector selector = new BlockingCloseSelector();
      clientSocket.selector = selector;
      AtomicReference<Throwable> readFailure = new AtomicReference<>();

      reader =
          new Thread(
              () -> {
                try {
                  inputStream.read();
                } catch (Throwable t) {
                  readFailure.set(t);
                }
              },
              "tunneling-jdk-socket-reader");
      reader.setDaemon(true);
      reader.start();

      assertTrue(
          selector.awaitSelectStarted(5, TimeUnit.SECONDS),
          "The reader did not block in Selector.select");
      clientSocket.close();
      reader.join(TimeUnit.SECONDS.toMillis(5));

      assertFalse(reader.isAlive(), "The blocked read did not terminate after close");
      Throwable failure = readFailure.get();
      assertNotNull(failure, "The blocked read should fail when the socket is closed");
      assertTrue(
          failure instanceof IOException,
          () -> "Expected an IOException, but got " + failure.getClass().getName());
      assertFalse(failure instanceof ClosedSelectorException);
    } finally {
      clientSocket.close();
      if (reader != null && reader.isAlive()) {
        reader.interrupt();
        reader.join(TimeUnit.SECONDS.toMillis(5));
      }
    }
  }

  private long getFileDescriptorCount() {
    try {
      Process process = Runtime.getRuntime().exec("lsof -p " + ProcessHandle.current().pid());
      int count = 0;
      try (java.io.BufferedReader reader =
          new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
        while (reader.readLine() != null) {
          count++;
        }
      }
      return count;
    } catch (IOException e) {
      throw new RuntimeException("Failed to get file descriptor count", e);
    }
  }

  private void startServer(UnixDomainSocketAddress socketAddress) throws IOException {
    server = new TestUnixSocketServer(socketAddress);
  }

  private Path getSocketPath() throws IOException {
    Path socketPath = Files.createTempFile("testSocket", null);
    Files.delete(socketPath);
    socketPath.toFile().deleteOnExit();
    return socketPath;
  }

  private TunnelingJdkSocket createClient() throws IOException {
    Path socketPath = getSocketPath();
    UnixDomainSocketAddress socketAddress = UnixDomainSocketAddress.of(socketPath);
    startServer(socketAddress);
    TunnelingJdkSocket clientSocket = new TunnelingJdkSocket(socketPath);
    clientSocket.connect(new InetSocketAddress("localhost", 0));
    return clientSocket;
  }

  private static final class TestUnixSocketServer implements AutoCloseable {
    private final Path socketPath;
    private final ServerSocketChannel serverChannel;
    private final AtomicReference<SocketChannel> acceptedChannel = new AtomicReference<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final Thread serverThread;

    private TestUnixSocketServer(UnixDomainSocketAddress socketAddress) throws IOException {
      socketPath = socketAddress.getPath();
      serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
      boolean bound = false;
      try {
        serverChannel.bind(socketAddress);
        bound = true;
      } finally {
        if (!bound) {
          serverChannel.close();
        }
      }

      serverThread =
          new Thread(
              () -> {
                try {
                  acceptedChannel.set(serverChannel.accept());
                } catch (IOException e) {
                  if (serverChannel.isOpen()) {
                    serverFailure.set(e);
                  }
                }
              },
              "tunneling-jdk-socket-test-server");
      serverThread.setDaemon(true);
      serverThread.start();
    }

    @Override
    public void close() throws Exception {
      serverChannel.close();
      serverThread.join(TimeUnit.SECONDS.toMillis(5));
      if (serverThread.isAlive()) {
        serverThread.interrupt();
        throw new AssertionError("The Unix-domain test server did not terminate");
      }

      SocketChannel clientChannel = acceptedChannel.get();
      if (clientChannel != null) {
        clientChannel.close();
      }
      Files.deleteIfExists(socketPath);

      Throwable failure = serverFailure.get();
      if (failure != null) {
        throw new AssertionError("The Unix-domain test server failed", failure);
      }
    }
  }

  private abstract static class SelectorAdapter extends Selector {
    private volatile boolean open = true;

    @Override
    public final boolean isOpen() {
      return open;
    }

    @Override
    public final SelectorProvider provider() {
      return SelectorProvider.provider();
    }

    @Override
    public final int selectNow() {
      return doSelect();
    }

    @Override
    public final int select(long timeout) {
      return doSelect();
    }

    @Override
    public final int select() {
      return doSelect();
    }

    @Override
    public final Selector wakeup() {
      return this;
    }

    @Override
    public final void close() {
      if (open) {
        open = false;
        onClose();
      }
    }

    abstract int doSelect();

    void onClose() {}
  }

  /**
   * Models another thread closing a selector while a socket read is blocked:
   *
   * <ol>
   *   <li>Signals when {@code select()} is entered.
   *   <li>Blocks until another thread closes the selector.
   *   <li>Throws {@link ClosedSelectorException} after closure.
   *   <li>Lets the test verify that the reader receives an {@link IOException} and terminates.
   * </ol>
   */
  private static final class BlockingCloseSelector extends SelectorAdapter {
    private final CountDownLatch selectStarted = new CountDownLatch(1);
    private final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public Set<SelectionKey> keys() {
      return Collections.emptySet();
    }

    @Override
    public Set<SelectionKey> selectedKeys() {
      return Collections.emptySet();
    }

    @Override
    int doSelect() {
      selectStarted.countDown();
      try {
        if (!closed.await(5, TimeUnit.SECONDS)) {
          throw new AssertionError("Selector was not closed");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for the selector to close", e);
      }
      throw new ClosedSelectorException();
    }

    @Override
    void onClose() {
      closed.countDown();
    }

    boolean awaitSelectStarted(long timeout, TimeUnit unit) throws InterruptedException {
      return selectStarted.await(timeout, unit);
    }
  }

  /**
   * Models a selector closing between selection and selected-key processing:
   *
   * <ol>
   *   <li>Closes itself during {@code select()} and reports one ready channel.
   *   <li>Throws {@link ClosedSelectorException} when the selected keys are requested.
   *   <li>Lets the test verify that the exception is reported as a {@link SocketException}.
   * </ol>
   */
  private static final class ClosedAfterSelectSelector extends SelectorAdapter {
    @Override
    public Set<SelectionKey> keys() {
      return Collections.emptySet();
    }

    @Override
    public Set<SelectionKey> selectedKeys() {
      if (!isOpen()) {
        throw new ClosedSelectorException();
      }
      return Collections.emptySet();
    }

    @Override
    int doSelect() {
      close();
      return 1;
    }
  }

  /**
   * Models a key being cancelled before selected-key processing:
   *
   * <ol>
   *   <li>Reports one ready channel from {@code select()}.
   *   <li>Returns an invalid selected key.
   *   <li>Throws {@link CancelledKeyException} when the read checks whether the key is readable.
   *   <li>Lets the test verify that the exception is reported as a {@link SocketException}.
   * </ol>
   */
  private static final class CancelledKeySelector extends SelectorAdapter {
    private final Set<SelectionKey> selectedKeys =
        new HashSet<>(Collections.singleton(new CancelledSelectionKey(this)));

    @Override
    public Set<SelectionKey> keys() {
      return selectedKeys;
    }

    @Override
    public Set<SelectionKey> selectedKeys() {
      return selectedKeys;
    }

    @Override
    int doSelect() {
      return 1;
    }

    private static final class CancelledSelectionKey extends SelectionKey {
      private final Selector selector;

      private CancelledSelectionKey(Selector selector) {
        this.selector = selector;
      }

      @Override
      public SelectableChannel channel() {
        return null;
      }

      @Override
      public Selector selector() {
        return selector;
      }

      @Override
      public boolean isValid() {
        return false;
      }

      @Override
      public void cancel() {}

      @Override
      public int interestOps() {
        return OP_READ;
      }

      @Override
      public SelectionKey interestOps(int ops) {
        return this;
      }

      @Override
      public int readyOps() {
        throw new CancelledKeyException();
      }
    }
  }

  private static boolean udsSupported() {
    Path socketPath = null;
    try {
      socketPath = Files.createTempFile("testSocketSupport", null);
      Files.delete(socketPath);
      try (ServerSocketChannel serverChannel =
          ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
      }
      return true;
    } catch (IOException | UnsupportedOperationException e) {
      return false;
    } finally {
      if (socketPath != null) {
        try {
          Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
        }
      }
    }
  }
}
