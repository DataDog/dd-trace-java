package foo.bar.smoketest;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;

public class DummyHttpServlet extends HttpServlet {

  DummyHttpServlet() {}

  private void callPublicServiceMethod(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    service((ServletRequest) req, (ServletResponse) resp);
  }

  @Override
  public void service(ServletRequest req, ServletResponse res)
      throws ServletException, IOException {
    // do nothing
  }

  @Override
  public ServletConfig getServletConfig() {
    return new ServletConfig() {
      @Override
      public String getServletName() {
        return "test";
      }

      @Override
      public ServletContext getServletContext() {
        return new DummyContext();
      }

      @Override
      public String getInitParameter(String s) {
        return s;
      }

      @Override
      public Enumeration<String> getInitParameterNames() {
        return null;
      }
    };
  }
}
