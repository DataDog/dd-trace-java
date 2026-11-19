package datadog.trace.instrumentation.jetty12;

import static datadog.trace.api.config.AppSecConfig.APPSEC_HTTP_BLOCKED_TEMPLATE_HTML;
import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.instrumentation.jetty12.JettyRawHttp.contentLength;
import static datadog.trace.instrumentation.jetty12.JettyRawHttp.largeHtmlTemplate;
import static datadog.trace.instrumentation.jetty12.JettyRawHttp.readBody;
import static datadog.trace.instrumentation.jetty12.JettyRawHttp.readHeaders;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.injectSysConfig;
import static datadog.trace.test.junit.utils.config.WithConfigExtension.removeSysConfig;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Jetty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks that a Jetty 12 ee10 request blocked after {@code startAsync()} (no timeout) is answered
 * and completed, see {@code JettyAsyncBlockCompletion}.
 *
 * <p>On Jetty 12.1+ ({@code ee10LatestDepForkedTest}) the connection is kept alive, so a second
 * request on it is checked. Jetty 12.0 ({@code ee10ForkedTest}) aborts the committed request and
 * closes the connection, so only the page and the server span are checked there. The Jetty version
 * is read at runtime: the forked tasks do not get the {@code test.dd.latestDepTest} property. Every
 * read is bounded, so a suspended request makes the test fail instead of hang.
 *
 * <p>Forked: the custom template, {@code APPSEC_ACTIVE} and the AppSec subscriptions are JVM-wide.
 */
@Timeout(value = 60, unit = SECONDS)
class AsyncBlockingCompletionForkedTest extends AbstractInstrumentationTest {
  private static final String CONTEXT_PATH = "/context-path";
  private static final String BLOCK_PATH = "/async-block";
  private static final String LISTENER_BLOCK_PATH = "/async-block-listener";
  private static final String UNCOMMITTED_BLOCK_PATH = "/async-block-uncommitted";
  private static final String OK_PATH = "/ok";
  private static final int SMALL_BUFFER_SIZE = 4 * 1024;
  private static final int TIMEOUT_SECONDS = 10;
  private static final int NO_RESPONSE_TIMEOUT_MILLIS = 2000;

  @TempDir Path tempDir;

  private final CountDownLatch handlerDone = new CountDownLatch(1);
  private volatile Throwable servletFailure;
  private Server server;
  private int port;
  // typed as Object: bootstrap classes are not allowed in test class field signatures
  private Object subscriptions;
  private boolean originalAppSecActive;

  @BeforeEach
  void setUp() {
    enableAppSec();
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
  void asyncBlockedRequestIsAnsweredAndCompleted() throws Exception {
    // Jetty 12.1+ keeps the connection open once the agent completes the suspended request
    boolean keepAliveExpected = isJetty121OrLater();
    startServer(false);
    try (Socket socket = connect(false)) {
      sendGet(socket, BLOCK_PATH);
      assertTrue(handlerDone.await(TIMEOUT_SECONDS, SECONDS), "handler did not complete");
      assertNull(servletFailure, "servlet setup failure");

      InputStream in = socket.getInputStream();
      String headers = readHeaders(in);
      assertTrue(headers.startsWith("HTTP/1.1 403 "), "unexpected status line: " + headers);
      int contentLength = contentLength(headers);
      assertTrue(contentLength > 0, "Content-Length of the blocking page");
      String body = new String(readBody(in, contentLength), UTF_8);
      assertTrue(body.toLowerCase(Locale.ROOT).contains("<html"), "blocking page: " + body);

      if (keepAliveExpected) {
        // without completion the connection is still busy with the suspended request
        sendGet(socket, OK_PATH);
        String okHeaders = readHeaders(in);
        assertTrue(
            okHeaders.startsWith("HTTP/1.1 200 "),
            "second request on the same connection: " + okHeaders);
        assertEquals("ok", new String(readBody(in, contentLength(okHeaders)), UTF_8));
      }
    }

    List<DDSpan> roots = awaitServerSpans(keepAliveExpected ? 2 : 1);
    assertSingleBlockedSpan(roots);
    if (keepAliveExpected) {
      DDSpan okSpan = roots.get(0).getHttpStatusCode() == 403 ? roots.get(1) : roots.get(0);
      assertEquals(200, okSpan.getHttpStatusCode());
    }
  }

  /** Jetty aborts once a listener completes in onError (committed response): no keep-alive. */
  @Test
  void asyncBlockedRequestCompletedByListener() throws Exception {
    startServer(false);
    try (Socket socket = connect(false)) {
      sendGet(socket, LISTENER_BLOCK_PATH);
      assertTrue(handlerDone.await(TIMEOUT_SECONDS, SECONDS), "handler did not complete");
      assertNull(servletFailure, "servlet setup failure");

      InputStream in = socket.getInputStream();
      String headers = readHeaders(in);
      assertTrue(headers.startsWith("HTTP/1.1 403 "), "unexpected status line: " + headers);
      int contentLength = contentLength(headers);
      assertTrue(contentLength > 0, "Content-Length of the blocking page");
      readBody(in, contentLength);
    }

    assertSingleBlockedSpan(awaitServerSpans(1));
  }

  /**
   * The block write is still in flight when the request is completed: the accepted outcome is the
   * full page and a finished span, the connection may be closed afterwards.
   */
  @Test
  void asyncBlockedRequestWithBlockWriteInFlight() throws Exception {
    byte[] template = largeHtmlTemplate();
    Path templateFile = tempDir.resolve("blocked.html");
    Files.write(templateFile, template);
    injectSysConfig(APPSEC_HTTP_BLOCKED_TEMPLATE_HTML, templateFile.toString());
    BlockingActionHelper.reset(Config.get());

    startServer(true);
    try (Socket socket = connect(true)) {
      sendGet(socket, BLOCK_PATH);
      // do not read until the servlet threw: the block write is then still pending
      assertTrue(handlerDone.await(TIMEOUT_SECONDS, SECONDS), "handler did not complete");
      assertNull(servletFailure, "servlet setup failure");
      // let Jetty process the BlockingException before the client starts reading
      Thread.sleep(500);

      InputStream in = socket.getInputStream();
      String headers = readHeaders(in);
      assertTrue(headers.startsWith("HTTP/1.1 403 "), "unexpected status line: " + headers);
      assertEquals(template.length, contentLength(headers), "Content-Length of the blocking page");
      assertArrayEquals(template, readBody(in, template.length), "blocking page content");
    }

    assertSingleBlockedSpan(awaitServerSpans(1));
  }

  /**
   * The servlet throws a {@code BlockingException} without a committed blocking response (as RASP
   * does when the commit failed): the agent must not complete it, which would send an empty 200.
   */
  @Test
  void asyncBlockedRequestWithoutBlockingResponseIsNotCompleted() throws Exception {
    boolean jetty121OrLater = isJetty121OrLater();
    startServer(false);
    try (Socket socket = connect(false)) {
      sendGet(socket, UNCOMMITTED_BLOCK_PATH);
      assertTrue(handlerDone.await(TIMEOUT_SECONDS, SECONDS), "handler did not complete");
      assertNull(servletFailure, "servlet setup failure");

      InputStream in = socket.getInputStream();
      if (jetty121OrLater) {
        // upstream behaviour: the request stays suspended and nothing is written
        socket.setSoTimeout(NO_RESPONSE_TIMEOUT_MILLIS);
        assertThrows(SocketTimeoutException.class, in::read, "no response expected");
      } else {
        // Jetty 12.0 sends an error response for an uncommitted async request
        String headers = readHeaders(in);
        assertTrue(headers.startsWith("HTTP/1.1 500 "), "unexpected status line: " + headers);
      }
    }
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

  private void startServer(boolean smallSendBuffer) throws Exception {
    server = new Server();
    ServerConnector connector = new ServerConnector(server);
    connector.setPort(0);
    if (smallSendBuffer) {
      connector.setAcceptedSendBufferSize(SMALL_BUFFER_SIZE);
    }
    server.addConnector(connector);

    ServletContextHandler handler = new ServletContextHandler(CONTEXT_PATH);
    handler.addFilter(
        new FilterHolder(new LatchFilter()), "/*", EnumSet.of(DispatcherType.REQUEST));
    addAsyncServlet(handler, new AsyncBlockingServlet(false, true), BLOCK_PATH);
    addAsyncServlet(handler, new AsyncBlockingServlet(true, true), LISTENER_BLOCK_PATH);
    addAsyncServlet(handler, new AsyncBlockingServlet(false, false), UNCOMMITTED_BLOCK_PATH);
    handler.addServlet(new ServletHolder(new OkServlet()), OK_PATH);
    server.setHandler(handler);
    server.start();
    port = connector.getLocalPort();
    assertTrue(port > 0, "server port");
  }

  private static void addAsyncServlet(
      ServletContextHandler handler, HttpServlet servlet, String path) {
    ServletHolder holder = new ServletHolder(servlet);
    holder.setAsyncSupported(true);
    handler.addServlet(holder, path);
  }

  private Socket connect(boolean smallReceiveBuffer) throws IOException {
    Socket socket = new Socket();
    if (smallReceiveBuffer) {
      // small receive window, so the server cannot hand the whole page to the kernel at once
      socket.setReceiveBufferSize(SMALL_BUFFER_SIZE);
    }
    socket.connect(new InetSocketAddress("localhost", port), TIMEOUT_SECONDS * 1000);
    socket.setSoTimeout(TIMEOUT_SECONDS * 1000);
    return socket;
  }

  private void sendGet(Socket socket, String path) throws IOException {
    OutputStream out = socket.getOutputStream();
    out.write(
        ("GET "
                + CONTEXT_PATH
                + path
                + " HTTP/1.1\r\nHost: localhost:"
                + port
                + "\r\nAccept: text/html\r\n\r\n")
            .getBytes(US_ASCII));
    out.flush();
  }

  /** Waits a bounded time for {@code traces} traces and returns the single root span of each. */
  private List<DDSpan> awaitServerSpans(int traces) throws Exception {
    assertTrue(
        writer.waitForTracesMax(traces, TIMEOUT_SECONDS),
        "expected " + traces + " trace(s), got " + writer.size() + ": " + writer);
    // give a wrongly duplicated trace write the chance to show up
    Thread.sleep(200);
    assertEquals(traces, writer.size(), "number of traces");
    List<DDSpan> roots = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      DDSpan root = null;
      for (DDSpan span : trace) {
        if (span.getParentId() == 0) {
          assertNull(root, "more than one server span in a trace");
          root = span;
        }
      }
      assertNotNull(root, "server span");
      roots.add(root);
    }
    return roots;
  }

  private static void assertSingleBlockedSpan(List<DDSpan> roots) {
    DDSpan blocked = null;
    for (DDSpan root : roots) {
      if (root.getHttpStatusCode() == 403) {
        assertNull(blocked, "more than one blocked server span: " + roots);
        blocked = root;
      }
    }
    assertNotNull(blocked, "no server span with status 403: " + roots);
    assertEquals("true", String.valueOf(blocked.getTag("appsec.blocked")));
  }

  private static boolean isJetty121OrLater() {
    String[] version = Jetty.VERSION.split("\\.");
    assertTrue(version.length >= 2, "unexpected Jetty version: " + Jetty.VERSION);
    int major = Integer.parseInt(version[0]);
    int minor = Integer.parseInt(version[1]);
    return major > 12 || (major == 12 && minor >= 1);
  }

  /** Counts down once the blocking servlet returned or threw, rethrowing the failure to Jetty. */
  private final class LatchFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      try {
        chain.doFilter(request, response);
      } finally {
        if (!((HttpServletRequest) request).getRequestURI().endsWith(OK_PATH)) {
          handlerDone.countDown();
        }
      }
    }
  }

  /**
   * Starts async without timeout, commits the blocking response, then throws like RASP SSRF in
   * {@code ProxyServlet}. Optionally registers an {@code AsyncListener} completing in onError, or
   * skips the commit to mimic a failed one.
   */
  private final class AsyncBlockingServlet extends HttpServlet {
    private final boolean withListener;
    private final boolean commitBlockingResponse;

    AsyncBlockingServlet(boolean withListener, boolean commitBlockingResponse) {
      this.withListener = withListener;
      this.commitBlockingResponse = commitBlockingResponse;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
      AsyncContext ac = req.startAsync();
      ac.setTimeout(0);
      if (withListener) {
        ac.addListener(new CompletingListener());
      }
      if (!commitBlockingResponse) {
        throw new BlockingException("Blocked after startAsync without a blocking response");
      }
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
      throw new BlockingException("Blocked after startAsync");
    }
  }

  /** Completes the async request when Jetty reports the error. */
  private static final class CompletingListener implements AsyncListener {
    @Override
    public void onError(AsyncEvent event) {
      event.getAsyncContext().complete();
    }

    @Override
    public void onComplete(AsyncEvent event) {}

    @Override
    public void onTimeout(AsyncEvent event) {}

    @Override
    public void onStartAsync(AsyncEvent event) {}
  }

  /** Plain endpoint used to check that the connection is still usable. */
  private static final class OkServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
      byte[] body = "ok".getBytes(UTF_8);
      resp.setContentType("text/plain");
      resp.setContentLength(body.length);
      resp.getOutputStream().write(body);
    }
  }
}
