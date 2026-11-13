package test.tomcat;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.rum.RumInjector;
import datadog.trace.test.junit.utils.config.WithConfig;
import jakarta.servlet.GenericServlet;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
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
    Tomcat.addServlet(context, path, servlet);
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
