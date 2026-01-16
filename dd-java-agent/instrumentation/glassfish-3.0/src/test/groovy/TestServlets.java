import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED;
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED_IS;
import static datadog.trace.agent.test.base.HttpServerTest.controller;

import com.google.common.io.CharStreams;
import datadog.trace.agent.test.base.HttpServerTest;
import groovy.lang.Closure;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.annotation.WebFilter;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class TestServlets {

  @WebServlet("/success")
  public static class Success extends HttpServlet {
    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
      final HttpServerTest.ServerEndpoint endpoint =
          HttpServerTest.ServerEndpoint.forPath(req.getServletPath());
      HttpServerTest.controller(
          endpoint,
          new Closure(null) {
            public Object doCall() throws Exception {
              resp.setContentType("text/plain");
              resp.setStatus(endpoint.getStatus());
              resp.getWriter().print(endpoint.getBody());
              return null;
            }
          });
    }
  }

  @WebServlet("/forwarded")
  public static class Forwarded extends HttpServlet {
    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
      final HttpServerTest.ServerEndpoint endpoint =
          HttpServerTest.ServerEndpoint.forPath(req.getServletPath());
      HttpServerTest.controller(
          endpoint,
          new Closure(null) {
            public Object doCall() throws Exception {
              resp.setContentType("text/plain");
              resp.setStatus(endpoint.getStatus());
              resp.getWriter().print(req.getHeader("x-forwarded-for"));
              return null;
            }
          });
    }
  }

  @WebServlet("/body-urlencoded")
  public static class BodyUrlEncoded extends HttpServlet {
    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
      HttpServerTest.controller(
          HttpServerTest.ServerEndpoint.BODY_URLENCODED,
          new Closure(null) {
            public Object doCall() throws Exception {
              resp.setContentType("text/plain");
              resp.setStatus(HttpServerTest.ServerEndpoint.BODY_URLENCODED.getStatus());
              resp.getWriter().print("[a:" + Arrays.asList(req.getParameterValues("a")) + "]");
              return null;
            }
          });
    }
  }

  @WebServlet({"/query", "/encoded_query", "/encoded path query"})
  public static class Query extends HttpServlet {
    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
      final HttpServerTest.ServerEndpoint endpoint =
          HttpServerTest.ServerEndpoint.forPath(req.getServletPath());
      HttpServerTest.controller(
          endpoint,
          new Closure(null) {
            public Object doCall() throws Exception {
              resp.setContentType("text/plain");
              resp.setStatus(endpoint.getStatus());
              resp.getWriter().print(endpoint.getBody());
              return null;
            }
          });
    }
  }

  @WebServlet("/redirect")
  public static class Redirect extends HttpServlet {
    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
      final HttpServerTest.ServerEndpoint endpoint =
          HttpServerTest.ServerEndpoint.forPath(req.getServletPath());
      HttpServerTest.controller(
          endpoint,
          new Closure(null) {
            public Object doCall() throws Exception {
              resp.sendRedirect(endpoint.getBody());
              return null;
            }
          });
    }
  }

  @WebServlet("/error-status")
  public static class Error extends HttpServlet {
    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
      final HttpServerTest.ServerEndpoint endpoint =
          HttpServerTest.ServerEndpoint.forPath(req.getServletPath());
      HttpServerTest.controller(
          endpoint,
          new Closure(null) {
            public Object doCall() throws Exception {
              resp.setContentType("text/plain");
              resp.sendError(endpoint.getStatus(), endpoint.getBody());
              return null;
            }
          });
    }
  }

  @WebServlet("/exception")
  public static class ExceptionServlet extends HttpServlet {
    @Override
    protected void service(final HttpServletRequest req, final HttpServletResponse resp) {
      final HttpServerTest.ServerEndpoint endpoint =
          HttpServerTest.ServerEndpoint.forPath(req.getServletPath());
      HttpServerTest.controller(
          endpoint,
          new Closure(null) {
            public Object doCall() throws Exception {
              throw new Exception(endpoint.getBody());
            }
          });
    }
  }

  @WebServlet("/created")
  public static class CreatedServlet extends HttpServlet {
    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) {
      controller(
          CREATED,
          new Closure(this) {
            public Object doCall() throws IOException {
              resp.setStatus(CREATED.getStatus());
              resp.getWriter()
                  .print(CREATED.getBody() + ": " + CharStreams.toString(req.getReader()));
              return null;
            }
          });
    }
  }

  @WebServlet("/created_input_stream")
  public static class CreatedISServlet extends HttpServlet {
    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) {
      controller(
          CREATED_IS,
          new Closure(this) {
            public Object doCall() throws IOException {
              resp.setStatus(CREATED_IS.getStatus());
              resp.getWriter()
                  .print(
                      CREATED_IS.getBody()
                          + ": "
                          + CharStreams.toString(
                              new InputStreamReader(req.getInputStream(), StandardCharsets.UTF_8)));
              return null;
            }
          });
    }
  }

  @WebFilter(urlPatterns = "/*")
  public static class ResponseHeaderFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}

    @Override
    public void doFilter(
        ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain)
        throws IOException, ServletException {
      if (servletResponse instanceof HttpServletResponse) {
        HttpServletResponse response = (HttpServletResponse) servletResponse;
        response.addHeader(
            HttpServerTest.getIG_RESPONSE_HEADER(), HttpServerTest.getIG_RESPONSE_HEADER_VALUE());
      }
      filterChain.doFilter(servletRequest, servletResponse);
    }

    @Override
    public void destroy() {}
  }
}
