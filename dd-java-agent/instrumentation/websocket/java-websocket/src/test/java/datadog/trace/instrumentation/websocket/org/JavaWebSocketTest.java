package datadog.trace.instrumentation.websocket.org;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;
import org.junit.jupiter.api.Test;

class JavaWebSocketTest extends AbstractInstrumentationTest {

  @Test
  void tracesClientAndServerWebsocketLifecycle() throws Exception {
    EchoServer server = new EchoServer();
    server.start();
    assertTrue(server.started.await(5, TimeUnit.SECONDS));

    TestClient client = new TestClient(new URI("ws://localhost:" + server.getPort()));
    client.connect();
    assertTrue(client.opened.await(5, TimeUnit.SECONDS));
    assertTrue(server.opened.await(5, TimeUnit.SECONDS));

    client.send("hello");
    assertTrue(server.textReceived.await(5, TimeUnit.SECONDS));
    assertTrue(client.textReceived.await(5, TimeUnit.SECONDS));
    assertEquals("echo:hello", client.textMessage.get());

    byte[] payload = new byte[] {1, 2, 3};
    client.send(ByteBuffer.wrap(payload));
    assertTrue(server.binaryReceived.await(5, TimeUnit.SECONDS));
    assertTrue(client.binaryReceived.await(5, TimeUnit.SECONDS));
    assertArrayEquals(payload, client.binaryMessage.get());

    client.close(1000, "done");
    assertTrue(client.closed.await(5, TimeUnit.SECONDS));
    assertTrue(server.closed.await(5, TimeUnit.SECONDS));
    server.stop(1000);

    blockUntilTracesMatch(traces -> countByOperation(flatten(traces), "websocket.open") >= 2);

    List<DDSpan> spans = flatten(writer);
    assertTrue(
        countByOperation(spans, "websocket.receive") >= 4,
        () -> "Expected websocket.receive spans. Spans: " + summarize(spans));
    assertTrue(
        countByOperation(spans, "websocket.send") >= 4,
        () -> "Expected websocket.send spans. Spans: " + summarize(spans));
    assertTrue(
        countByOperation(spans, "websocket.close") >= 2,
        () -> "Expected websocket.close spans. Spans: " + summarize(spans));

    DDSpan clientOpen = findSpan(spans, "websocket.open", Tags.SPAN_KIND_CLIENT);
    DDSpan serverOpen = findSpan(spans, "websocket.open", Tags.SPAN_KIND_SERVER);
    assertNotNull(clientOpen);
    assertNotNull(serverOpen);
    assertEquals(clientOpen.getSpanId(), serverOpen.getParentId());

    assertNotNull(findMessageSpan(spans, "text", 5));
    assertNotNull(findMessageSpan(spans, "binary", payload.length));

    for (DDSpan span : spans) {
      if (span.getOperationName().toString().startsWith("websocket.")) {
        assertEquals("org-java-websocket", String.valueOf(span.getTag(Tags.COMPONENT)));
        assertEquals("websocket", String.valueOf(span.getSpanType()));
      }
    }
  }

  private static int countByOperation(List<DDSpan> spans, String operationName) {
    int count = 0;
    for (DDSpan span : spans) {
      if (operationName.equals(span.getOperationName().toString())) {
        count++;
      }
    }
    return count;
  }

  private static DDSpan findSpan(List<DDSpan> spans, String operationName, String spanKind) {
    for (DDSpan span : spans) {
      if (operationName.equals(span.getOperationName().toString())
          && spanKind.equals(span.getTag(Tags.SPAN_KIND))) {
        return span;
      }
    }
    return null;
  }

  private static DDSpan findMessageSpan(List<DDSpan> spans, String messageType, int messageSize) {
    for (DDSpan span : spans) {
      if ("websocket.receive".equals(span.getOperationName().toString())
          && messageType.equals(String.valueOf(span.getTag("message.type")))
          && Integer.valueOf(messageSize).equals(span.getTag("message.size"))) {
        return span;
      }
    }
    return null;
  }

  private static List<String> summarize(List<DDSpan> spans) {
    List<String> summary = new ArrayList<>();
    for (DDSpan span : spans) {
      summary.add(
          span.getOperationName()
              + "/"
              + span.getTag(Tags.SPAN_KIND)
              + "/"
              + span.getTag("message.type")
              + "/"
              + span.getTag("message.size"));
    }
    return summary;
  }

  private static List<DDSpan> flatten(List<List<DDSpan>> traces) {
    List<DDSpan> result = new ArrayList<>();
    for (List<DDSpan> trace : traces) {
      result.addAll(trace);
    }
    return result;
  }

  static class EchoServer extends WebSocketServer {
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch opened = new CountDownLatch(1);
    final CountDownLatch textReceived = new CountDownLatch(1);
    final CountDownLatch binaryReceived = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);

    EchoServer() {
      super(new InetSocketAddress("localhost", 0));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
      opened.countDown();
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
      textReceived.countDown();
      conn.send("echo:" + message);
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer message) {
      byte[] bytes = new byte[message.remaining()];
      message.get(bytes);
      binaryReceived.countDown();
      conn.send(ByteBuffer.wrap(bytes));
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
      closed.countDown();
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {}

    @Override
    public void onStart() {
      started.countDown();
    }
  }

  static class TestClient extends WebSocketClient {
    final CountDownLatch opened = new CountDownLatch(1);
    final CountDownLatch textReceived = new CountDownLatch(1);
    final CountDownLatch binaryReceived = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    final AtomicReference<String> textMessage = new AtomicReference<>();
    final AtomicReference<byte[]> binaryMessage = new AtomicReference<>();

    TestClient(URI serverUri) {
      super(serverUri);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
      opened.countDown();
    }

    @Override
    public void onMessage(String message) {
      textMessage.set(message);
      textReceived.countDown();
    }

    @Override
    public void onMessage(ByteBuffer bytes) {
      byte[] message = new byte[bytes.remaining()];
      bytes.get(message);
      binaryMessage.set(message);
      binaryReceived.countDown();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
      closed.countDown();
    }

    @Override
    public void onError(Exception ex) {}
  }
}
