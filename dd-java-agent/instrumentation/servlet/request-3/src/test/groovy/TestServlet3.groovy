import datadog.trace.agent.test.base.HttpServerTest
import groovy.servlet.AbstractHttpServlet

import javax.servlet.annotation.WebServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.Phaser

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.REDIRECT
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class TestServlet3 {

  @WebServlet
  static class Sync extends AbstractHttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      HttpServerTest.ServerEndpoint endpoint = HttpServerTest.ServerEndpoint.forPath(req.servletPath)
      HttpServerTest.controller(endpoint) {
        resp.contentType = "text/plain"
        switch (endpoint) {
          case SUCCESS:
            resp.status = endpoint.status
            resp.writer.print(endpoint.body)
            break
          case REDIRECT:
            resp.sendRedirect(endpoint.body)
            break
          case ERROR:
            resp.sendError(endpoint.status, endpoint.body)
            break
          case EXCEPTION:
            throw new Exception(endpoint.body)
        }
      }
    }
  }

  @WebServlet(asyncSupported = true)
  static class Async extends AbstractHttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      HttpServerTest.ServerEndpoint endpoint = HttpServerTest.ServerEndpoint.forPath(req.servletPath)
      def phaser = new Phaser(2)
      def context = req.startAsync()
      context.start {
        try {
          phaser.arrive()
          HttpServerTest.controller(endpoint) {
            resp.contentType = "text/plain"
            switch (endpoint) {
              case SUCCESS:
              case ERROR:
                resp.status = endpoint.status
                resp.writer.print(endpoint.body)
                context.complete()
                break
              case REDIRECT:
                resp.sendRedirect(endpoint.body)
                context.complete()
                break
              case EXCEPTION:
                resp.status = endpoint.status
                resp.writer.print(endpoint.body)
                context.complete()
                throw new Exception(endpoint.body)
            }
          }
        } finally {
          phaser.arriveAndDeregister()
        }
      }
      phaser.arriveAndAwaitAdvance()
      phaser.arriveAndAwaitAdvance()
    }
  }

  @WebServlet(asyncSupported = true)
  static class FakeAsync extends AbstractHttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      def context = req.startAsync()
      try {
        HttpServerTest.ServerEndpoint endpoint = HttpServerTest.ServerEndpoint.forPath(req.servletPath)
        HttpServerTest.controller(endpoint) {
          resp.contentType = "text/plain"
          switch (endpoint) {
            case SUCCESS:
            case ERROR:
              resp.status = endpoint.status
              resp.writer.print(endpoint.body)
              break
            case REDIRECT:
              resp.sendRedirect(endpoint.body)
              break
            case EXCEPTION:
              throw new Exception(endpoint.body)
          }
        }
      } finally {
        context.complete()
      }
    }
  }

  @WebServlet(asyncSupported = true)
  static class DispatchImmediate extends AbstractHttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      def target = req.servletPath.replace("/dispatch", "")
      req.startAsync().dispatch(target)
    }
  }

  @WebServlet(asyncSupported = true)
  static class DispatchAsync extends AbstractHttpServlet {
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
  static class DispatchRecursive extends AbstractHttpServlet {
    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) {
      if (req.servletPath.equals("/recursive")) {
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
}
