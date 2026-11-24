package datadog.trace.instrumentation.jetty12;

import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_HTML;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.removeSysConfig;
import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.core.DDSpan;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee8.servlet.FilterHolder;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks that a large mid-handler blocking response is not truncated on Jetty 12 ee8.
 *
 * <p>Mirrors {@code MidHandlerBlockingTruncationEe9ForkedTest} on the ee8 nested servlet API.
 *
 * <p>Forked: the custom template, {@code APPSEC_ACTIVE} and the AppSec subscriptions are JVM-wide.
 */
class MidHandlerBlockingTruncationEe8ForkedTest extends AbstractInstrumentationTest {
  private static final String CONTEXT_PATH = "/context-path";
  private static final String BLOCK_PATH = "/mid-handler-block";
  private static final int TEMPLATE_SIZE = 400 * 1024;
  private static final int SMALL_BUFFER_SIZE = 4 * 1024;
  private static final int TIMEOUT_SECONDS = 10;

  @TempDir Path tempDir;

  private final CountDownLatch handlerDone = new CountDownLatch(1);
  private volatile Throwable servletFailure;
  private Server server;
  private int port;
  // typed as Object: bootstrap classes are not allowed in test class field signatures
  private Object subscriptions;
  private boolean originalAppSecActive;
  private byte[] template;

  @BeforeEach
  void setUp() throws Exception {
    template = largeHtmlTemplate();
    Path templateFile = tempDir.resolve("blocked.html");
    Files.write(templateFile, template);
    injectSysConfig(APPSEC_HTTP_BLOCKED_TEMPLATE_HTML, templateFile.toString());
    BlockingActionHelper.reset(Config.get());

    enableAppSec();
    startServer();
  }

  @AfterEach
  void cleanUp() throws Exception {
    try {
      if (server != null) {
        server.stop();
      }
    } finally {
      if (subscriptions != null) {
        ((SubscriptionService) subscriptions).reset();
        ActiveSubsystems.APPSEC_ACTIVE = originalAppSecActive;
      }
      removeSysConfig(APPSEC_HTTP_BLOCKED_TEMPLATE_HTML);
      BlockingActionHelper.reset(Config.get());
    }
  }

  @Test
  void midHandlerBlockingResponseReachesSlowClientInFull() throws Exception {
    try (Socket socket = new Socket()) {
      // small receive window, so the server cannot hand the whole page to the kernel at once
      socket.setReceiveBufferSize(SMALL_BUFFER_SIZE);
      socket.connect(new InetSocketAddress("localhost", port), TIMEOUT_SECONDS * 1000);
      socket.setSoTimeout(TIMEOUT_SECONDS * 1000);

      OutputStream out = socket.getOutputStream();
      out.write(
          ("GET "
                  + CONTEXT_PATH
                  + BLOCK_PATH
                  + " HTTP/1.1\r\nHost: localhost:"
                  + port
                  + "\r\nAccept: text/html\r\n\r\n")
              .getBytes(US_ASCII));
      out.flush();

      // do not read until the handler threw: the block write is then still pending
      assertTrue(handlerDone.await(TIMEOUT_SECONDS, SECONDS), "handler did not complete");
      assertNull(servletFailure, "servlet setup failure");
      // let Jetty process the BlockingException before the client starts reading
      Thread.sleep(500);

      InputStream in = socket.getInputStream();
      String headers = readHeaders(in);
      assertTrue(headers.startsWith("HTTP/1.1 403 "), "unexpected status line: " + headers);
      int contentLength = contentLength(headers);
      assertEquals(template.length, contentLength, "Content-Length of the blocking page");

      byte[] body = readBody(in, contentLength);
      assertArrayEquals(template, body, "blocking page content");
    }

    DDSpan serverSpan = awaitSingleServerSpan();
    assertEquals(403, serverSpan.getHttpStatusCode());
    assertEquals("true", String.valueOf(serverSpan.getTag("appsec.blocked")));
  }

  private void enableAppSec() {
    SubscriptionService ss =
        (SubscriptionService) AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC);
    subscriptions = ss;
    originalAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;
    // a RequestContext must exist for JettyDecorator to register the BlockResponseFunction
    ss.registerCallback(
        EVENTS.requestStarted(),
        new Supplier<Flow<Object>>() {
          @Override
          public Flow<Object> get() {
            return new Flow.ResultFlow<>(new Object());
          }
        });
  }

  private void startServer() throws Exception {
    server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(0);
    connector.setAcceptedSendBufferSize(SMALL_BUFFER_SIZE);
    server.addConnector(connector);

    ServletContextHandler handler =
        new ServletContextHandler((Handler.Container) null, CONTEXT_PATH);
    handler.addFilter(
        new FilterHolder(new LatchFilter()), "/*", EnumSet.of(DispatcherType.REQUEST));
    handler.addServlet(new ServletHolder(new MidHandlerBlockingServlet()), BLOCK_PATH);
    server.setHandler(handler.getCoreContextHandler());
    server.start();
    port = connector.getLocalPort();
    assertTrue(port > 0, "server port");
  }

  private DDSpan awaitSingleServerSpan() throws Exception {
    writer.waitForTraces(1);
    // give a wrongly duplicated trace write the chance to show up
    Thread.sleep(200);
    assertEquals(1, writer.size(), "number of traces");
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

  private static byte[] largeHtmlTemplate() {
    String head = "<!DOCTYPE html><html><head><title>Blocked</title></head><body>\n";
    String tail = "</body></html>\n";
    StringBuilder sb = new StringBuilder(TEMPLATE_SIZE).append(head);
    int line = 0;
    while (sb.length() + tail.length() < TEMPLATE_SIZE) {
      sb.append(String.format(Locale.ROOT, "<p>blocked line %08d</p>\n", line++));
    }
    sb.setLength(TEMPLATE_SIZE - tail.length());
    return sb.append(tail).toString().getBytes(UTF_8);
  }

  private static String readHeaders(InputStream in) throws IOException {
    ByteArrayOutputStream headers = new ByteArrayOutputStream();
    int matched = 0;
    while (matched < 4) {
      int b = in.read();
      if (b < 0) {
        fail("connection closed while reading headers: " + headers.toString("ISO-8859-1"));
      }
      headers.write(b);
      matched = (b == "\r\n\r\n".charAt(matched)) ? matched + 1 : (b == '\r' ? 1 : 0);
    }
    return new String(headers.toByteArray(), ISO_8859_1);
  }

  private static int contentLength(String headers) {
    for (String line : headers.split("\r\n")) {
      int colon = line.indexOf(':');
      if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
        return Integer.parseInt(line.substring(colon + 1).trim());
      }
    }
    return fail("no Content-Length in response headers: " + headers);
  }

  /** Reads exactly {@code length} bytes, failing with the received count on EOF or reset. */
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

  private static String truncated(int received, int length, String reason) {
    return "blocking page truncated: received "
        + received
        + " of "
        + length
        + " body bytes before "
        + reason;
  }

  /** Counts down once the handler chain returned or threw, rethrowing the failure to Jetty. */
  private final class LatchFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void destroy() {}

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      try {
        chain.doFilter(request, response);
      } finally {
        handlerDone.countDown();
      }
    }
  }

  /** Commits the blocking response mid-handler, then throws like RASP and the blocking SDK. */
  private final class MidHandlerBlockingServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
      AgentSpan span = AgentTracer.activeSpan();
      RequestContext reqCtx = span == null ? null : span.getRequestContext();
      BlockResponseFunction brf = reqCtx == null ? null : reqCtx.getBlockResponseFunction();
      if (brf == null) {
        servletFailure = new AssertionError("no BlockResponseFunction, span: " + span);
        throw new IllegalStateException("no BlockResponseFunction");
      }
      boolean committed =
          brf.tryCommitBlockingResponse(
              reqCtx, new Flow.Action.RequestBlockingAction(403, BlockingContentType.HTML));
      if (!committed) {
        servletFailure = new AssertionError("blocking response was not committed");
        throw new IllegalStateException("blocking response was not committed");
      }
      throw new BlockingException("Blocked mid-handler");
    }
  }
}
