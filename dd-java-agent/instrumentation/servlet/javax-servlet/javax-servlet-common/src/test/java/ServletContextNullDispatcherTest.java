import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Set;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import org.junit.jupiter.api.Test;

/**
 * Servlet containers return a {@code null} dispatcher for unknown paths or servlet names (e.g.
 * Tomcat's {@code ApplicationContext}); the instrumentation must not try to store context against a
 * {@code null} key. Instrumentation errors fail the test via {@link AbstractInstrumentationTest}.
 */
class ServletContextNullDispatcherTest extends AbstractInstrumentationTest {

  @Test
  void getRequestDispatcherReturningNull() {
    assertNull(new NullDispatcherContext().getRequestDispatcher("unknown"));
  }

  @Test
  void getNamedDispatcherReturningNull() {
    assertNull(new NullDispatcherContext().getNamedDispatcher("unknown"));
  }

  /** {@link ServletContext} that has no dispatchers, like Tomcat for an unknown target. */
  static class NullDispatcherContext implements ServletContext {
    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
      return null;
    }

    @Override
    public RequestDispatcher getNamedDispatcher(String name) {
      return null;
    }

    @Override
    public ServletContext getContext(String uripath) {
      return null;
    }

    @Override
    public int getMajorVersion() {
      return 2;
    }

    @Override
    public int getMinorVersion() {
      return 3;
    }

    @Override
    public String getMimeType(String file) {
      return null;
    }

    @Override
    public Set getResourcePaths(String path) {
      return null;
    }

    @Override
    public URL getResource(String path) {
      return null;
    }

    @Override
    public InputStream getResourceAsStream(String path) {
      return null;
    }

    @Override
    public Servlet getServlet(String name) {
      return null;
    }

    @Override
    public Enumeration getServlets() {
      return null;
    }

    @Override
    public Enumeration getServletNames() {
      return null;
    }

    @Override
    public void log(String msg) {}

    @Override
    public void log(Exception exception, String msg) {}

    @Override
    public void log(String message, Throwable throwable) {}

    @Override
    public String getRealPath(String path) {
      return null;
    }

    @Override
    public String getServerInfo() {
      return null;
    }

    @Override
    public String getInitParameter(String name) {
      return null;
    }

    @Override
    public Enumeration getInitParameterNames() {
      return null;
    }

    @Override
    public Object getAttribute(String name) {
      return null;
    }

    @Override
    public Enumeration getAttributeNames() {
      return null;
    }

    @Override
    public void setAttribute(String name, Object object) {}

    @Override
    public void removeAttribute(String name) {}

    @Override
    public String getServletContextName() {
      return null;
    }
  }
}
