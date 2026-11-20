package test.tomcat;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDTags;
import datadog.trace.api.rum.RumInjector;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Servlets that never close their writer rely on the instrumentation committing the RUM wrapper
 * when {@code service} exits. Otherwise the injecting writer keeps holding the last characters it
 * buffered while looking for {@code </head>}, and they are dropped from the response.
 */
@WithConfig(key = "trace.servlet.principal.enabled", value = "true")
@WithConfig(key = "rum.enabled", value = "true")
@WithConfig(key = "rum.application.id", value = "test")
@WithConfig(key = "rum.client.token", value = "secret")
@WithConfig(key = "rum.remote.configuration.id", value = "12345")
class RumInjectionUnclosedWriterForkedTest extends AbstractInstrumentationTest {
  private static final String PAGE_WITH_HEAD =
      "<!doctype html>\n<html>\n<head><title>t</title></head>\n<body>with head</body>\n</html>";
  // the marker is never found, so the injecting writer still buffers the end of the page on exit
  private static final String PAGE_WITHOUT_HEAD =
      "<!doctype html>\n<html>\n<body>no head element</body>\n</html>";

  private static Tomcat tomcat;
  private static int port;

  @BeforeAll
  static void startTomcat() throws Exception {
    File baseDir = Files.createTempDirectory("tomcat-rum").toFile();
    baseDir.deleteOnExit();
    tomcat = new Tomcat();
    tomcat.setBaseDir(baseDir.getAbsolutePath());
    tomcat.setPort(0);
    Context context = tomcat.addContext("", baseDir.getAbsolutePath());
    addServlet(context, "/async/without-head", new AsyncDispatchHttpServlet());
    addServlet(context, "/http/with-head", new UnclosedHttpServlet(PAGE_WITH_HEAD));
    addServlet(context, "/http/without-head", new UnclosedHttpServlet(PAGE_WITHOUT_HEAD));
    addServlet(context, "/generic/with-head", new UnclosedGenericServlet(PAGE_WITH_HEAD));
    addServlet(context, "/generic/without-head", new UnclosedGenericServlet(PAGE_WITHOUT_HEAD));
    tomcat.getConnector();
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

  @Test
  void httpServletPageWithHeadIsInjectedAndComplete() throws IOException {
    assertInjected("/http/with-head");
  }

  @Test
  void httpServletPageWithoutHeadIsComplete() throws IOException {
    assertNotInjected("/http/without-head");
  }

  @Test
  void genericServletPageWithHeadIsInjectedAndComplete() throws IOException {
    assertInjected("/generic/with-head");
  }

  @Test
  void genericServletPageWithoutHeadIsComplete() throws IOException {
    assertNotInjected("/generic/without-head");
  }

  @Test
  void rumDrainsBeforeTracingExtractsPrincipal() throws Exception {
    AgentSpan span = tracer.startSpan("test", "servlet");
    try {
      HttpServletRequest request = request(span);
      StringWriter body = new StringWriter();
      AtomicBoolean drainedAtPrincipalExtraction = new AtomicBoolean();
      when(request.getUserPrincipal())
          .thenAnswer(invocation -> {
            drainedAtPrincipalExtraction.set(PAGE_WITHOUT_HEAD.equals(body.toString()));
            return (java.security.Principal) () -> "user";
          });
      new PublicHttpServlet(PAGE_WITHOUT_HEAD).service(request, response(body));
      assertEquals(PAGE_WITHOUT_HEAD, body.toString());
      assertTrue(
          drainedAtPrincipalExtraction.get(), "RUM must exit before tracing reads the principal");
      assertEquals("user", span.getTag(DDTags.USER_NAME));
    } finally {
      span.finish();
    }
  }

  @Test
  void genericServletDoesNotTakeTracingOwnershipFromNestedHttpServlet() throws Exception {
    AgentSpan span = tracer.startSpan("test", "servlet");
    try {
      HttpServletRequest request = request(span);
      when(request.getUserPrincipal()).thenReturn(() -> "outer");
      StringWriter body = new StringWriter();
      new DelegatingGenericServlet().service(request, response(body));
      String snippet = new String(RumInjector.get().getSnippetBytes("UTF-8"), UTF_8);
      assertEquals(PAGE_WITH_HEAD.replace("</head>", snippet + "</head>"), body.toString());
      assertEquals("inner", span.getTag(DDTags.USER_NAME));
    } finally {
      span.finish();
    }
  }

  @Test
  void genericServletDrainsWithoutTracingContext() throws Exception {
    StringWriter body = new StringWriter();
    new UnclosedGenericServlet(PAGE_WITHOUT_HEAD).service(request(null), response(body));
    assertEquals(PAGE_WITHOUT_HEAD, body.toString());
  }

  @Test
  void genericServletDoesNotExtractPrincipal() throws Exception {
    AgentSpan span = tracer.startSpan("test", "servlet");
    try {
      HttpServletRequest request = request(span);
      when(request.getUserPrincipal()).thenReturn(() -> "generic");
      StringWriter body = new StringWriter();
      new UnclosedGenericServlet(PAGE_WITHOUT_HEAD).service(request, response(body));
      assertEquals(PAGE_WITHOUT_HEAD, body.toString());
      assertNull(span.getTag(DDTags.USER_NAME));
    } finally {
      span.finish();
    }
  }

  @Test
  void nestedServletDoesNotDrainOwnersPartialMarker() throws Exception {
    StringWriter body = new StringWriter();
    new NestedGenericServlet().service(request(null), response(body));
    String snippet = new String(RumInjector.get().getSnippetBytes("UTF-8"), UTF_8);
    assertEquals("<head>" + snippet + "</head>", body.toString());
  }

  static final class NestedGenericServlet extends GenericServlet {
    @Override
    public void service(ServletRequest request, ServletResponse response) throws IOException {
      response.setContentType("text/html;charset=UTF-8");
      response.getWriter().print("<head></he");
      new UnclosedGenericServlet("").service(request, response);
      response.getWriter().print("ad>");
    }
  }

  @Test
  void failedGenericServletDrainsWithoutTracingContext() throws Exception {
    StringWriter body = new StringWriter();
    IOException error =
        assertThrows(
            IOException.class,
            () -> new FailingGenericServlet().service(request(null), response(body)));
    assertEquals("servlet failure", error.getMessage());
    assertEquals(PAGE_WITHOUT_HEAD, body.toString());
  }

  static final class FailingGenericServlet extends GenericServlet {
    @Override
    public void service(ServletRequest request, ServletResponse response) throws IOException {
      response.setContentType("text/html;charset=UTF-8");
      response.getWriter().print(PAGE_WITHOUT_HEAD);
      throw new IOException("servlet failure");
    }
  }

  private static HttpServletRequest request(Object span) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    Map<String, Object> attributes = new HashMap<>();
    attributes.put(DD_CONTEXT_ATTRIBUTE, span);
    when(request.getAttribute(anyString()))
        .thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
    doAnswer(
            invocation -> {
              attributes.put(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(request)
        .setAttribute(anyString(), any());
    return request;
  }

  private static HttpServletResponse response(StringWriter body) throws IOException {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenReturn(new PrintWriter(body));
    when(response.getCharacterEncoding()).thenReturn("UTF-8");
    return response;
  }

  static final class PublicHttpServlet extends HttpServlet {
    private final String page;

    PublicHttpServlet(String page) {
      this.page = page;
    }

    @Override
    public void service(ServletRequest request, ServletResponse response) throws IOException {
      response.setContentType("text/html;charset=UTF-8");
      response.getWriter().print(page);
    }
  }

  static final class DelegatingGenericServlet extends GenericServlet {
    @Override
    public void service(ServletRequest request, ServletResponse response) throws IOException {
      HttpServletRequest inner =
          new HttpServletRequestWrapper((HttpServletRequest) request) {
            @Override
            public java.security.Principal getUserPrincipal() {
              return () -> "inner";
            }
          };
      new PublicHttpServlet(PAGE_WITH_HEAD).service(inner, response);
    }
  }

  @Test
  void asyncRedispatchDrainsReusedWrapper() throws IOException {
    assertNotInjected("/async/without-head");
  }

  static final class AsyncDispatchHttpServlet extends HttpServlet {
    @Override
    public void service(ServletRequest request, ServletResponse response) {
      request.startAsync().dispatch("/generic/without-head");
    }
  }

  private static void assertInjected(String path) throws IOException {
    String snippet = new String(RumInjector.get().getSnippetBytes("UTF-8"), UTF_8);
    HttpURLConnection connection = get(path);
    assertEquals(PAGE_WITH_HEAD.replace("</head>", snippet + "</head>"), body(connection));
    assertEquals("1", connection.getHeaderField("x-datadog-rum-injected"));
  }

  private static void assertNotInjected(String path) throws IOException {
    HttpURLConnection connection = get(path);
    assertEquals(PAGE_WITHOUT_HEAD, body(connection));
    assertNull(connection.getHeaderField("x-datadog-rum-injected"));
  }

  private static HttpURLConnection get(String path) throws IOException {
    HttpURLConnection connection =
        (HttpURLConnection) new URL("http://localhost:" + port + path).openConnection();
    assertEquals(200, connection.getResponseCode());
    return connection;
  }

  private static String body(HttpURLConnection connection) throws IOException {
    try (InputStream in = connection.getInputStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      for (int read; (read = in.read(buffer)) != -1; ) {
        out.write(buffer, 0, read);
      }
      return new String(out.toByteArray(), UTF_8);
    }
  }

  private static void addServlet(Context context, String path, jakarta.servlet.Servlet servlet) {
    Tomcat.addServlet(context, path, servlet).setAsyncSupported(true);
    context.addServletMappingDecoded(path, path);
  }

  static final class UnclosedHttpServlet extends HttpServlet {
    private final String page;

    UnclosedHttpServlet(String page) {
      this.page = page;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
        throws IOException {
      response.setContentType("text/html;charset=UTF-8");
      response.getWriter().print(page);
    }
  }

  /**
   * Like {@code FacesServlet}, implements {@code Servlet} without extending {@code HttpServlet}.
   */
  static final class UnclosedGenericServlet extends GenericServlet {
    private final String page;

    UnclosedGenericServlet(String page) {
      this.page = page;
    }

    @Override
    public void service(ServletRequest request, ServletResponse response) throws IOException {
      response.setContentType("text/html;charset=UTF-8");
      response.getWriter().print(page);
    }
  }
}
