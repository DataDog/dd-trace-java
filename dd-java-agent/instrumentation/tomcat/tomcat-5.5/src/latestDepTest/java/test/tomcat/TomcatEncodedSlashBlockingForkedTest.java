package test.tomcat;

import static datadog.trace.api.gateway.Events.EVENTS;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.core.DDSpan;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks that a WAF block of a path Tomcat already rejected with {@code sendError(400)} (an encoded
 * slash under the default {@code encodedSolidusHandling=REJECT}) sends the complete block page
 * (APPSEC-71828). The block comes from the URI callback, as a URI WAF rule would.
 *
 * <p>Forked: {@code APPSEC_ACTIVE} and the AppSec subscriptions are JVM-wide.
 */
class TomcatEncodedSlashBlockingForkedTest extends AbstractInstrumentationTest {
  /** Raw paths starting with this prefix are blocked by the URI callback. */
  private static final String BLOCKED_PREFIX = "/blocked/";

  private static final int TIMEOUT_MILLIS = 5000;

  private static final AtomicInteger servletCalls = new AtomicInteger();

  private final Queue<String> blockedRawPaths = new ConcurrentLinkedQueue<>();
  private final Queue<String> allowedRawPaths = new ConcurrentLinkedQueue<>();

  private static Tomcat tomcat;
  private static int port;

  // typed as Object: bootstrap classes are not allowed in test class field signatures
  private Object subscriptions;
  private boolean originalAppSecActive;

  @BeforeAll
  static void startTomcat() throws Exception {
    File baseDir = Files.createTempDirectory("tomcat-encoded-slash").toFile();
    baseDir.deleteOnExit();
    tomcat = new Tomcat();
    tomcat.setBaseDir(baseDir.getAbsolutePath());
    tomcat.setPort(0);
    Context context = tomcat.addContext("", baseDir.getAbsolutePath());
    Tomcat.addServlet(context, "recording", new RecordingServlet());
    context.addServletMappingDecoded("/*", "recording");
    // pinned so the encoded slash is always rejected with sendError(400), the precondition here
    tomcat.getConnector().setEncodedSolidusHandling("reject");
    tomcat.start();
    port = tomcat.getConnector().getLocalPort();
  }

  @AfterAll
  static void stopTomcat() throws Exception {
    if (tomcat != null) {
      tomcat.stop();
      tomcat.destroy();
    }
  }

  @BeforeEach
  void enableAppSec() {
    servletCalls.set(0);

    SubscriptionService ss =
        (SubscriptionService) AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC);
    subscriptions = ss;
    originalAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
    // a RequestContext must exist for the URI callback to run and for the block to be committed
    ss.registerCallback(
        EVENTS.requestStarted(),
        new Supplier<Flow<Object>>() {
          @Override
          public Flow<Object> get() {
            return new Flow.ResultFlow<>(new Object());
          }
        });
    ss.registerCallback(
        EVENTS.requestMethodUriRaw(),
        new TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>() {
          @Override
          public Flow<Void> apply(RequestContext ctx, String method, URIDataAdapter uri) {
            String rawPath = uri.rawPath();
            if (rawPath != null && rawPath.startsWith(BLOCKED_PREFIX)) {
              blockedRawPaths.add(rawPath);
              return new Flow<Void>() {
                @Override
                public Action getAction() {
                  return new Action.RequestBlockingAction(403, BlockingContentType.JSON);
                }

                @Override
                public Void getResult() {
                  return null;
                }
              };
            }
            allowedRawPaths.add(String.valueOf(rawPath));
            return Flow.ResultFlow.empty();
          }
        });
  }

  @AfterEach
  void disableAppSec() {
    if (subscriptions != null) {
      ((SubscriptionService) subscriptions).reset();
      ActiveSubsystems.APPSEC_ACTIVE = originalAppSecActive;
    }
  }

  @Test
  void blockedEncodedSlashPathGetsCompleteBlockPage() throws Exception {
    assertCompleteBlockPage(BLOCKED_PREFIX + "..%2f..%2fetc%2fpasswd");
  }

  @Test
  void unblockedEncodedSlashPathIsRejectedByTomcat() throws Exception {
    String path = "/allowed/..%2f..%2fetc%2fpasswd";
    Response response = get(path, false);

    assertEquals(400, response.status, response.headers);
    assertTrue(response.body.length > 0, "Tomcat error page body");
    assertEquals(0, servletCalls.get(), "servlet must not run on a rejected request");
    assertTrue(blockedRawPaths.isEmpty(), "URI callback must not block");
    assertTrue(allowedRawPaths.contains(path), "URI callback saw the raw path");

    DDSpan serverSpan = awaitServerSpan();
    assertEquals(400, serverSpan.getHttpStatusCode());
    assertNull(serverSpan.getTag("appsec.blocked"));
  }

  @Test
  void blockedPlainPathGetsCompleteBlockPage() throws Exception {
    assertCompleteBlockPage(BLOCKED_PREFIX + "plain");
  }

  private void assertCompleteBlockPage(String path) throws Exception {
    Response response = get(path, true);

    assertEquals(403, response.status, response.headers);
    assertArrayEquals(
        BlockingActionHelper.getTemplate(TemplateType.JSON, null),
        response.body,
        "block page content");
    assertEquals(0, servletCalls.get(), "servlet must not run on a blocked request");
    assertEquals(path, blockedRawPaths.peek(), "URI callback saw the raw path");

    DDSpan serverSpan = awaitServerSpan();
    assertEquals(403, serverSpan.getHttpStatusCode());
    assertEquals("true", String.valueOf(serverSpan.getTag("appsec.blocked")));
  }

  /**
   * Sends {@code GET path} verbatim (no dot-segment normalization) and reads the response. With
   * {@code requireContentLength}, the body is exactly Content-Length bytes and never read to EOF.
   */
  private static Response get(String path, boolean requireContentLength) throws IOException {
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress("localhost", port), TIMEOUT_MILLIS);
      socket.setSoTimeout(TIMEOUT_MILLIS);
      OutputStream out = socket.getOutputStream();
      out.write(
          ("GET "
                  + path
                  + " HTTP/1.1\r\nHost: localhost\r\nAccept: application/json\r\n"
                  + "Connection: close\r\n\r\n")
              .getBytes(US_ASCII));
      out.flush();

      InputStream in = socket.getInputStream();
      String headers = readHeaders(in);
      int status = status(headers);
      int contentLength = contentLength(headers);
      byte[] body;
      if (contentLength >= 0) {
        body = readBody(in, contentLength);
      } else if (requireContentLength) {
        return fail("no Content-Length in response headers: " + headers);
      } else {
        // no Content-Length: Tomcat closes the connection after the error page
        body = readToEof(in);
      }
      return new Response(status, headers, body);
    }
  }

  private static String readHeaders(InputStream in) throws IOException {
    ByteArrayOutputStream headers = new ByteArrayOutputStream();
    int matched = 0;
    try {
      while (matched < 4) {
        int b = in.read();
        if (b < 0) {
          fail("connection closed while reading headers: " + headers.toString("ISO-8859-1"));
        }
        headers.write(b);
        matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
      }
    } catch (SocketTimeoutException e) {
      fail("timed out reading headers: " + headers.toString("ISO-8859-1"), e);
    }
    return new String(headers.toByteArray(), ISO_8859_1);
  }

  private static int status(String headers) {
    String[] statusLine = headers.substring(0, headers.indexOf("\r\n")).split(" ");
    return Integer.parseInt(statusLine[1]);
  }

  /** Returns the Content-Length header value, or -1 when absent. */
  private static int contentLength(String headers) {
    for (String line : headers.split("\r\n")) {
      int colon = line.indexOf(':');
      if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
        return Integer.parseInt(line.substring(colon + 1).trim());
      }
    }
    return -1;
  }

  /** Reads exactly {@code length} bytes, failing with the received count on EOF or timeout. */
  private static byte[] readBody(InputStream in, int length) {
    byte[] body = new byte[length];
    int received = 0;
    try {
      while (received < length) {
        int n = in.read(body, received, length - received);
        if (n < 0) {
          fail(truncated(received, length, "EOF"));
        }
        received += n;
      }
    } catch (IOException e) {
      fail(truncated(received, length, e.toString()), e);
    }
    return body;
  }

  private static byte[] readToEof(InputStream in) throws IOException {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    try {
      for (int read; (read = in.read(buffer)) != -1; ) {
        body.write(buffer, 0, read);
      }
    } catch (SocketTimeoutException e) {
      fail("timed out waiting for the server to close the connection", e);
    }
    return body.toByteArray();
  }

  private static String truncated(int received, int length, String reason) {
    return "response body truncated: received "
        + received
        + " of "
        + length
        + " Content-Length bytes before "
        + reason;
  }

  private DDSpan awaitServerSpan() throws Exception {
    writer.waitForTraces(1);
    List<DDSpan> trace = writer.get(0);
    DDSpan root = null;
    for (DDSpan span : trace) {
      if (span.getParentId() == 0) {
        assertNull(root, "more than one server span");
        root = span;
      }
    }
    assertNotNull(root, "server span");
    return root;
  }

  private static final class Response {
    final int status;
    final String headers;
    final byte[] body;

    Response(int status, String headers, byte[] body) {
      this.status = status;
      this.headers = headers;
      this.body = body;
    }
  }

  /** Counts its invocations: blocked or rejected requests must never reach it. */
  static final class RecordingServlet extends HttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      servletCalls.incrementAndGet();
      resp.setContentType("text/plain");
      resp.getWriter().print("servlet ran");
    }
  }
}
