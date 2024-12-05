package datadog.trace.instrumentation.liberty23;

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SESSION_ID;

import datadog.trace.agent.test.base.HttpServerTest;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.annotation.MultipartConfig;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Enumeration;

@WebServlet(
    urlPatterns = {
      "/async/success",
      "/async/created",
      "/async/created_input_stream",
      "/async/body-urlencoded",
      "/async/body-multipart",
      "/async/body-json",
      "/async/redirect",
      "/async/forwarded",
      "/async/error-status",
      "/async/exception",
      "/async/custom-exception",
      "/async/not-here",
      "/async/timeout",
      "/async/timeout_error",
      "/async/query",
      "/async/encoded path query",
      "/async/encoded_query",
      "/async/user-block",
      "/async/session",
    },
    asyncSupported = true)
@MultipartConfig(
    maxFileSize = 10 * 1024 * 1024,
    maxRequestSize = 20 * 1024 * 1024,
    fileSizeThreshold = 5 * 1024 * 1024)
public class AsyncServlet5 extends HttpServlet {
  datadog.trace.instrumentation.servlet5.TestServlet5 delegate;

  {
    try {
      delegate =
          new datadog.trace.instrumentation.servlet5.TestServlet5() {
            @Override
            public HttpServerTest.ServerEndpoint determineEndpoint(HttpServletRequest req) {
              return HttpServerTest.ServerEndpoint.forPath(
                  req.getRequestURI().substring(req.getRequestURI().lastIndexOf('/')));
            }
          };
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void service(ServletRequest req, final ServletResponse res)
      throws ServletException, IOException {
    Object attribute = req.getAttribute("ddog.dispatched");

    if (attribute == null) {
      AsyncContext asyncContext = req.startAsync();
      req.setAttribute("ddog.dispatched", 1);
      asyncContext.dispatch();
    } else {
      HttpServerTest.ServerEndpoint serverEndpoint =
          delegate.determineEndpoint((HttpServletRequest) req);
      if (serverEndpoint == BODY_MULTIPART) {
        // needed to trigger reading the body on openliberty
        ((HttpServletRequest) req).getParts();
      } else if (serverEndpoint == ERROR
          || serverEndpoint == QUERY_ENCODED_BOTH
          || serverEndpoint == SESSION_ID) {
        delegate.service(req, res);
        return;
      }

      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      PrintWriter writer = new PrintWriter(baos);
      HttpServletResponseWrapper wrappedRes =
          new HttpServletResponseWrapper((HttpServletResponse) res) {
            @Override
            public PrintWriter getWriter() throws IOException {
              return writer;
            }
          };

      delegate.service(req, wrappedRes);
      writer.flush();

      AsyncContext asyncContext = req.startAsync();
      asyncContext.setTimeout(1000);
      asyncContext.addListener(
          new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) throws IOException {
              log("onComplete");
            }

            @Override
            public void onTimeout(AsyncEvent event) throws IOException {
              log("onTimeout");
            }

            @Override
            public void onError(AsyncEvent event) throws IOException {
              log("onError", event.getThrowable());
            }

            @Override
            public void onStartAsync(AsyncEvent event) throws IOException {
              event.getAsyncContext().addListener(this);
            }
          });

      ServletOutputStream outputStream;
      try {
        outputStream = res.getOutputStream();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      WriteListener listener =
          new WriteListener() {
            @Override
            public void onWritePossible() throws IOException {
              outputStream.write(baos.toByteArray());
              asyncContext.complete();
            }

            @Override
            public void onError(Throwable e) {
              log("onError", e);
              asyncContext.complete();
            }
          };

      try {
        Method setWriteListener =
            ServletOutputStream.class.getMethod("setWriteListener", WriteListener.class);
        setWriteListener.invoke(outputStream, listener);
      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException(e);
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
