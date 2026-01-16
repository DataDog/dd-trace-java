package datadog.trace.instrumentation.servlet3

import datadog.appsec.api.blocking.Blocking
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint

import javax.servlet.AsyncEvent
import javax.servlet.AsyncListener
import javax.servlet.annotation.WebServlet
import javax.servlet.http.HttpServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.lang.reflect.Field
import java.lang.reflect.Modifier

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_URLENCODED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.BODY_MULTIPART
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CREATED_IS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_QUERY
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SESSION_ID
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.TIMEOUT_ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.USER_BLOCK

class TestServlet3 {

  public static final long SERVLET_TIMEOUT = 1000

  static HttpServerTest.ServerEndpoint getEndpoint(HttpServletRequest req) {
    String truePath
    if (req.servletPath == "") {
      truePath = req.requestURI - ~'^/[^/]+'
    } else {
      // Most correct would be to get the dispatched path from the request
      // This is not part of the spec varies by implementation so the simplest is just removing
      // "/dispatch"
      truePath = req.servletPath.replace("/dispatch", "")
    }
    return HttpServerTest.ServerEndpoint.forPath(truePath)
  }

  @WebServlet
  static class Sync extends HttpServlet {
    ServerEndpoint determineEndpoint(HttpServletRequest req) {
      getEndpoint(req)
    }

    // this method is not instrumented by the servlet advice
    @Override
    void service(HttpServletRequest req, HttpServletResponse resp) {
      HttpServerTest.ServerEndpoint endpoint = determineEndpoint(req)
      HttpServerTest.controller(endpoint) {
        resp.contentType = "text/plain"
        resp.addHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
        switch (endpoint) {
          case SUCCESS:
            resp.status = endpoint.status
            resp.writer.print(endpoint.body)
            break
          case CREATED:
            resp.status = endpoint.status
            resp.writer.print("${endpoint.body}: ${req.reader.text}")
            break
          case CREATED_IS:
            resp.status = endpoint.status
            def stream = req.inputStream
            resp.writer.print("${endpoint.body}: ${stream.getText('UTF-8')}")
            try {
              Field f = stream.getClass().getField('is')
              def innerStream = f.get(stream)
              def method = innerStream.getClass().getMethod('isFinished')
              if ((method.getModifiers() & Modifier.ABSTRACT) == 0) {
                if (!stream.isFinished()) {
                  throw new RuntimeException("Not finished")
                }
              }
            } catch (NoSuchMethodException | NoSuchFieldException mnf) {}
            break
          case FORWARDED:
            resp.status = endpoint.status
            resp.writer.print(req.getHeader("x-forwarded-for"))
            break
          case QUERY_ENCODED_BOTH:
          case QUERY_ENCODED_QUERY:
          case QUERY_PARAM:
            resp.status = endpoint.status
            resp.writer.print(endpoint.bodyForQuery(req.queryString))
            break
          case BODY_URLENCODED:
          case BODY_MULTIPART:
            resp.status = endpoint.status
            resp.writer.print(
              req.parameterMap
              .findAll{
                it.key != 'ignore'
              }
              .collectEntries {[it.key, it.value as List]} as String)
            break
          case REDIRECT:
            resp.sendRedirect(endpoint.body)
            break
          case ERROR:
            resp.sendError(endpoint.status, endpoint.body)
            break
          case EXCEPTION:
            throw new Exception(endpoint.body)
          case USER_BLOCK:
            Blocking.forUser('user-to-block').blockIfMatch()
            break
          case CUSTOM_EXCEPTION:
            throw new InputMismatchException(endpoint.body)
          case SESSION_ID:
            req.getSession(true)
            resp.status = endpoint.status
            resp.writer.print(req.requestedSessionId)
            break
        }
      }
    }
  }

  @WebServlet(asyncSupported = true)
  static class Async extends HttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      HttpServerTest.ServerEndpoint endpoint = getEndpoint(req)
      def context = req.startAsync()
      context.setTimeout(SERVLET_TIMEOUT)
      if (resp.class.name.startsWith("org.eclipse.jetty")) {
        // this line makes Jetty behave like Tomcat and immediately return 500 to the client
        // otherwise it will continue to repeat the same request until the client times out
        context.addListener(new AsyncListener() {
            void onComplete(AsyncEvent event) throws IOException {}

            void onError(AsyncEvent event) throws IOException {}

            void onStartAsync(AsyncEvent event) throws IOException {}

            @Override
            void onTimeout(AsyncEvent event) throws IOException {
              event.suppliedResponse.status = 500
              event.asyncContext.complete()
            }
          })
      }
      context.start {
        HttpServerTest.controller(endpoint) {
          resp.contentType = "text/plain"
          resp.addHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
          switch (endpoint) {
            case SUCCESS:
              resp.status = endpoint.status
              resp.writer.print(endpoint.body)
              context.complete()
              break
            case CREATED:
              resp.status = endpoint.status
              resp.writer.print("${endpoint.body}: ${req.reader.text}")
              break
            case CREATED_IS:
              resp.status = endpoint.status
              resp.writer.print("${endpoint.body}: ${req.inputStream.getText('UTF-8')}")
              break
            case FORWARDED:
              resp.status = endpoint.status
              resp.writer.print(req.getHeader("x-forwarded-for"))
              context.complete()
              break
            case QUERY_ENCODED_BOTH:
            case QUERY_ENCODED_QUERY:
            case QUERY_PARAM:
              resp.status = endpoint.status
              resp.writer.print(endpoint.bodyForQuery(req.queryString))
              context.complete()
              break
            case REDIRECT:
              resp.sendRedirect(endpoint.body)
              context.complete()
              break
            case ERROR:
              resp.sendError(endpoint.status, endpoint.body)
              context.complete()
              break
            case EXCEPTION:
              throw new Exception(endpoint.body)
            case CUSTOM_EXCEPTION:
              throw new InputMismatchException(endpoint.body)
            case TIMEOUT:
            case TIMEOUT_ERROR:
              sleep context.getTimeout() + 10
              break
            case USER_BLOCK:
              Blocking.forUser('user-to-block').blockIfMatch()
              resp.writer.print('should not be reached')
              context.complete()
              break
            case SESSION_ID:
              req.getSession(true)
              resp.status = endpoint.status
              resp.writer.print(req.requestedSessionId)
              context.complete()
              break
          }
        }
      }
    }
  }

  @WebServlet(asyncSupported = true)
  static class FakeAsync extends HttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      def context = req.startAsync()
      try {
        HttpServerTest.ServerEndpoint endpoint = getEndpoint(req)
        HttpServerTest.controller(endpoint) {
          resp.contentType = "text/plain"
          resp.addHeader(HttpServerTest.IG_RESPONSE_HEADER, HttpServerTest.IG_RESPONSE_HEADER_VALUE)
          switch (endpoint) {
            case SUCCESS:
              resp.status = endpoint.status
              resp.writer.print(endpoint.body)
              break
            case CREATED:
              resp.status = endpoint.status
              resp.writer.print("${endpoint.body}: ${req.reader.text}")
              break
            case CREATED_IS:
              resp.status = endpoint.status
              resp.writer.print("${endpoint.body}: ${req.inputStream.getText('UTF-8')}")
              break
            case FORWARDED:
              resp.status = endpoint.status
              resp.writer.print(req.getHeader("x-forwarded-for"))
              break
            case QUERY_ENCODED_BOTH:
            case QUERY_ENCODED_QUERY:
            case QUERY_PARAM:
              resp.status = endpoint.status
              resp.writer.print(endpoint.bodyForQuery(req.queryString))
              break
            case REDIRECT:
              resp.sendRedirect(endpoint.body)
              break
            case ERROR:
              resp.sendError(endpoint.status, endpoint.body)
              break
            case EXCEPTION:
              throw new Exception(endpoint.body)
            case CUSTOM_EXCEPTION:
              throw new InputMismatchException(endpoint.body)
            case USER_BLOCK:
              Blocking.forUser('user-to-block').blockIfMatch()
              resp.writer.print('should not be reached')
              break
            case SESSION_ID:
              req.getSession(true)
              resp.status = endpoint.status
              resp.writer.print(req.requestedSessionId)
              break
          }
        }
      } finally {
        context.complete()
      }
    }
  }

  @WebServlet(asyncSupported = true)
  static class DispatchImmediate extends HttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      def target = req.servletPath.replace("/dispatch", "")
      req.startAsync().dispatch(target)
    }
  }

  @WebServlet(asyncSupported = true)
  static class DispatchAsync extends HttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      def target = req.servletPath.replace("/dispatch", "")
      def context = req.startAsync()
      context.start {
        context.dispatch(target)
      }
    }
  }

  // TODO: Add tests for this!
  @WebServlet(asyncSupported = true)
  static class DispatchRecursive extends HttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      if (req.servletPath == "/recursive") {
        resp.writer.print("Hello Recursive")
        return
      }
      def depth = Integer.parseInt(req.getParameter("depth"))
      if (depth > 0) {
        req.startAsync().dispatch("/dispatch/recursive?depth=" + (depth - 1))
      } else {
        req.startAsync().dispatch("/recursive")
      }
    }
  }

  @WebServlet
  static class GetSession extends Sync {
    @Override
    void service(HttpServletRequest req, HttpServletResponse resp) {
      req.getSession(true)
      super.service(req,resp)
    }
  }
}
