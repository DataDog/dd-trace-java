package datadog.trace.instrumentation.liberty20;

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART;

import java.io.IOException;
import java.util.Enumeration;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.MultipartConfig;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;

@WebServlet(
    urlPatterns = {
      "/success",
      "/created",
      "/created_input_stream",
      "/body-urlencoded",
      "/body-multipart",
      "/body-json",
      "/redirect",
      "/forwarded",
      "/error-status",
      "/exception",
      "/custom-exception",
      "/not-here",
      "/timeout",
      "/timeout_error",
      "/query",
      "/encoded path query",
      "/encoded_query",
      "/user-block",
    })
@MultipartConfig(
    maxFileSize = 10 * 1024 * 1024,
    maxRequestSize = 20 * 1024 * 1024,
    fileSizeThreshold = 5 * 1024 * 1024)
public class PassthruSyncServlet3 extends HttpServlet {
  datadog.trace.instrumentation.servlet3.TestServlet3.Sync delegate;

  {
    try {
      delegate = new datadog.trace.instrumentation.servlet3.TestServlet3.Sync();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void service(ServletRequest req, ServletResponse res)
      throws ServletException, IOException {
    if (delegate.determineEndpoint((HttpServletRequest) req) == BODY_MULTIPART) {
      // needed to trigger reading the body on openliberty
      ((HttpServletRequest) req).getParts();
    }
    try {
      delegate.service(req, res);
    } catch (Throwable t) {
      throw t;
    } finally {
      if (((HttpServletRequest) req).getHeader("x-commit-during-response") != null) {
        res.flushBuffer();
        res.getWriter().close();
        throw new RuntimeException("should not be reached");
      }
    }
  }

  @Override
  public void destroy() {
    delegate.destroy();
  }

  @Override
  public String getInitParameter(String name) {
    return delegate.getInitParameter(name);
  }

  @Override
  public Enumeration<String> getInitParameterNames() {
    return delegate.getInitParameterNames();
  }

  @Override
  public ServletConfig getServletConfig() {
    return delegate.getServletConfig();
  }

  @Override
  public ServletContext getServletContext() {
    return delegate.getServletContext();
  }

  @Override
  public String getServletInfo() {
    return delegate.getServletInfo();
  }

  @Override
  public void init() throws ServletException {
    delegate.init();
  }

  @Override
  public void init(ServletConfig config) throws ServletException {
    delegate.init(config);
  }

  @Override
  public String getServletName() {
    return delegate.getServletName();
  }

  @Override
  public void log(String msg) {
    delegate.log(msg);
  }

  @Override
  public void log(String msg, Throwable t) {
    delegate.log(msg, t);
  }
}
