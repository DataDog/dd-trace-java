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
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks that a large mid-handler blocking response is not truncated on Jetty 12 ee10.
 *
 * <p>Forked: the custom template, {@code APPSEC_ACTIVE} and the AppSec subscriptions are JVM-wide.
 */
class MidHandlerBlockingTruncationForkedTest extends AbstractInstrumentationTest {
  private static final String CONTEXT_PATH = "/context-path";
  private static final String BLOCK_PATH = "/mid-handler-block";
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

    ServletContextHandler handler = new ServletContextHandler(CONTEXT_PATH);
    handler.addFilter(
        new FilterHolder(new LatchFilter()), "/*", EnumSet.of(DispatcherType.REQUEST));
    handler.addServlet(new ServletHolder(new MidHandlerBlockingServlet()), BLOCK_PATH);
    server.setHandler(handler);
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

  /** Counts down once the handler chain returned or threw, rethrowing the failure to Jetty. */
  private final class LatchFilter implements Filter {
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
