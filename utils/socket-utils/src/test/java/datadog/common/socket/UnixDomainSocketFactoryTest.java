package datadog.common.socket;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.condition.JRE.JAVA_16;
import static org.mockito.Mockito.mockStatic;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import jnr.unixsocket.UnixSocketChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

@EnabledForJreRange(min = JAVA_16)
class UnixDomainSocketFactoryTest {
  /** The TCP address HTTP clients connect to. The factory tunnels it to the socket file. */
  private static final InetSocketAddress AGENT_ADDRESS =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 8126);

  private static final int CONNECT_TIMEOUT_MILLIS = 1000;

  private static final byte[] REQUEST = "PUT /v0.4/traces HTTP/1.1\r\n".getBytes(UTF_8);

  @TempDir Path tempDir;

  @Test
  void connectsToTheAgentSocketWithAJdkSocket() throws Exception {
    try (UnixSocketEchoServer agent = new UnixSocketEchoServer(socketPath());
        Socket socket =
            new UnixDomainSocketFactory(agent.path().toFile(), true, false).createSocket()) {
      assertInstanceOf(TunnelingJdkSocket.class, socket);

      socket.connect(AGENT_ADDRESS, CONNECT_TIMEOUT_MILLIS);

      assertExchangesBytesWithTheAgent(socket);
    }
  }

  @Test
  void connectsToTheAgentSocketWithJnrWhenJdkSocketsAreDisabled() throws Exception {
    try (UnixSocketEchoServer agent = new UnixSocketEchoServer(socketPath());
        Socket socket =
            new UnixDomainSocketFactory(agent.path().toFile(), false, false).createSocket()) {
      assertInstanceOf(TunnelingUnixSocket.class, socket);

      socket.connect(AGENT_ADDRESS, CONNECT_TIMEOUT_MILLIS);

      assertExchangesBytesWithTheAgent(socket);
      assertEquals(
          AGENT_ADDRESS.getAddress(),
          socket.getInetAddress(),
          "HTTP clients see the address they connected to");
    }
  }

  @ParameterizedTest(name = "JDK socket enabled: {0}")
  @ValueSource(booleans = {true, false})
  void connectsRightAwayWhenGivenTheAgentAddress(boolean jdkSocketEnabled) throws Exception {
    try (UnixSocketEchoServer agent = new UnixSocketEchoServer(socketPath())) {
      UnixDomainSocketFactory factory =
          new UnixDomainSocketFactory(agent.path().toFile(), jdkSocketEnabled, false);

      InetAddress localAddress = InetAddress.getLoopbackAddress();

      try (Socket socket = factory.createSocket("localhost", AGENT_ADDRESS.getPort())) {
        assertExchangesBytesWithTheAgent(socket);
      }
      try (Socket socket =
          factory.createSocket("localhost", AGENT_ADDRESS.getPort(), localAddress, 0)) {
        assertExchangesBytesWithTheAgent(socket);
      }
      try (Socket socket =
          factory.createSocket(AGENT_ADDRESS.getAddress(), AGENT_ADDRESS.getPort())) {
        assertExchangesBytesWithTheAgent(socket);
      }
      try (Socket socket =
          factory.createSocket(
              AGENT_ADDRESS.getAddress(), AGENT_ADDRESS.getPort(), localAddress, 0)) {
        assertExchangesBytesWithTheAgent(socket);
      }
    }
  }

  @Test
  void fallsBackToTheAgentPortWhenTheDiscoveredSocketIsUnusable() throws Exception {
    File socketFile = socketPath().toFile();
    try (MockedStatic<UnixSocketChannel> jnr = mockStatic(UnixSocketChannel.class)) {
      // jnr can't open a channel when its native library doesn't load on the platform
      jnr.when(UnixSocketChannel::open).thenThrow(new IOException("jnr is unavailable"));

      // the socket file wasn't configured: the tracer discovered it at its default path
      Socket socket = new UnixDomainSocketFactory(socketFile, false, true).createSocket();

      assertSame(Socket.class, socket.getClass(), "a plain TCP socket to the Agent port");
      assertFalse(socket.isConnected());
    }
  }

  @Test
  void reportsTheErrorWhenTheConfiguredSocketIsUnusable() throws Exception {
    File socketFile = socketPath().toFile();
    try (MockedStatic<UnixSocketChannel> jnr = mockStatic(UnixSocketChannel.class)) {
      jnr.when(UnixSocketChannel::open).thenThrow(new IOException("jnr is unavailable"));

      UnixDomainSocketFactory factory = new UnixDomainSocketFactory(socketFile, false, false);

      IOException error = assertThrows(IOException.class, factory::createSocket);
      assertEquals("jnr is unavailable", error.getMessage());
    }
  }

  private Path socketPath() {
    return this.tempDir.resolve("apm.socket");
  }

  private static void assertExchangesBytesWithTheAgent(Socket socket) throws IOException {
    assertTrue(socket.isConnected());
    OutputStream output = socket.getOutputStream();
    output.write(REQUEST);
    output.flush();

    byte[] echoed = new byte[REQUEST.length];
    new DataInputStream(socket.getInputStream()).readFully(echoed);
    assertArrayEquals(REQUEST, echoed);
  }
}
