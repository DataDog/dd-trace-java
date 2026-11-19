package datadog.common.socket;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A regular file stands in for the pipe, since NamedPipeSocket only opens it as a file. */
class NamedPipeSocketTest {
  private static final byte[] REQUEST = "PUT /v0.4/traces HTTP/1.1\r\n".getBytes(UTF_8);
  private static final byte[] RESPONSE = "HTTP/1.1 200 OK\r\n".getBytes(UTF_8);

  /** The Agent address HTTP clients connect to. The pipe ignores it. */
  private static final InetSocketAddress AGENT_ADDRESS =
      new InetSocketAddress(InetAddress.getLoopbackAddress(), 8126);

  private static final int TIMEOUT_MILLIS = 1000;

  @TempDir Path tempDir;

  @Test
  void writesRequestsToThePipe() throws Exception {
    File pipe = pipe();
    try (Socket socket = new NamedPipeSocket(pipe)) {
      socket.getOutputStream().write(REQUEST);
    }

    assertArrayEquals(REQUEST, Files.readAllBytes(pipe.toPath()));
  }

  @Test
  void readsResponsesFromThePipe() throws Exception {
    File pipe = pipe();
    Files.write(pipe.toPath(), RESPONSE);

    try (Socket socket = new NamedPipeSocket(pipe)) {
      byte[] response = new byte[RESPONSE.length];
      new DataInputStream(socket.getInputStream()).readFully(response);
      assertArrayEquals(RESPONSE, response);
    }
  }

  @Test
  void acceptsTheConnectionSetupOfHttpClients() throws Exception {
    try (Socket socket = new NamedPipeSocket(pipe())) {
      // OkHttp connects the socket to the Agent address, then sets its read timeout
      socket.connect(AGENT_ADDRESS, TIMEOUT_MILLIS);
      socket.setSoTimeout(TIMEOUT_MILLIS);

      assertTrue(socket.isConnected());
      assertEquals(0, socket.getSoTimeout(), "pipe reads can't time out");
    }
  }

  @Test
  void reportsItsStateSoHttpClientsDiscardClosedPipes() throws Exception {
    Socket socket = new NamedPipeSocket(pipe());
    assertTrue(socket.isConnected());
    assertTrue(socket.isBound());
    assertFalse(socket.isClosed());
    assertFalse(socket.isInputShutdown());
    assertFalse(socket.isOutputShutdown());

    socket.close();

    assertFalse(socket.isConnected());
    assertFalse(socket.isBound());
    assertTrue(socket.isClosed());
    assertTrue(socket.isInputShutdown());
    assertTrue(socket.isOutputShutdown());
    assertThrows(IOException.class, socket::getInputStream);
    assertThrows(IOException.class, socket::getOutputStream);
  }

  @Test
  void closesThePipeWhenEitherDirectionIsShutDown() throws Exception {
    // a pipe can't be half-closed
    try (Socket input = new NamedPipeSocket(pipe());
        Socket output = new NamedPipeSocket(pipe())) {
      input.shutdownInput();
      output.shutdownOutput();

      assertTrue(input.isClosed());
      assertTrue(output.isClosed());
    }
  }

  private File pipe() {
    return this.tempDir.resolve("pipe").toFile();
  }
}
