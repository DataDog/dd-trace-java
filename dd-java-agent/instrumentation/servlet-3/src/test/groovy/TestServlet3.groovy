import datadog.trace.agent.test.TestUtils
import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.context.TraceScope
import groovy.servlet.AbstractHttpServlet
import io.opentracing.util.GlobalTracer

import javax.servlet.annotation.WebServlet
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.ForkJoinPool

class TestServlet3 {

  @WebServlet
  static class Sync extends AbstractHttpServlet {
    @Override
    void doGet(HttpServletRequest req, HttpServletResponse resp) {
      if (req.getParameter("error") != null) {
        throw new RuntimeException("some sync error")
      }
      if (req.getParameter("non-throwing-error") != null) {
        resp.sendError(500, "some sync error")
        return
      }
      resp.writer.print("Hello Sync")
    }
  }

  @WebServlet(asyncSupported = true)
  static class Async extends AbstractHttpServlet {
    @Override
    void doGet(HttpServletRequest req, HttpServletResponse resp) {
      def context = req.startAsync()
      context.start {
        resp.writer.print("Hello Async")
        context.complete()
      }
    }
  }


  @WebServlet(asyncSupported = true)
  static class AsyncWithBackgroundWork extends AbstractHttpServlet {
    private static final POOL = new ForkJoinPool()

    @Override
    void doGet(HttpServletRequest req, HttpServletResponse resp) {
      def context = req.startAsync()

      final TraceScope.Continuation continuation = ((TraceScope) GlobalTracer.get().scopeManager().active()).capture()
      context.start {
        final TraceScope scope = continuation.activate()
        scope.setAsyncPropagation(true)
        resp.writer.print("AsyncWithBackground")
        context.complete()
        MutableSpan rootSpan = (MutableSpan) GlobalTracer.get().scopeManager().active().span()
        while (!rootSpan.isFinished()) {
          // await servlet span finish before submitting async
        }
        POOL.submit {
          TestUtils.runUnderTrace("not-http") {
            // This should not report as part of the servlet trace because http response is written.
          }
        }
        scope.close()
      }
    }
  }
}
