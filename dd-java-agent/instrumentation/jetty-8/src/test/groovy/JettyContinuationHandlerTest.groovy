import org.eclipse.jetty.continuation.Continuation
import org.eclipse.jetty.continuation.ContinuationSupport
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.handler.AbstractHandler

import javax.servlet.ServletException
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// FIXME: We don't currently handle jetty continuations properly (at all).
abstract class JettyContinuationHandlerTest extends JettyHandlerTest {

  @Override
  AbstractHandler handler() {
    ContinuationTestHandler.INSTANCE
  }

  static class ContinuationTestHandler extends AbstractHandler {
    static final ContinuationTestHandler INSTANCE = new ContinuationTestHandler()
    final ExecutorService executorService = Executors.newSingleThreadExecutor()

    @Override
    void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
      final Continuation continuation = ContinuationSupport.getContinuation(request)
      if (continuation.initial) {
        continuation.suspend()
        executorService.execute {
          continuation.resume()
        }
      } else {
        handleRequest(baseRequest, response)
      }
      baseRequest.handled = true
    }
  }

//  // This server seems to generate a TEST_SPAN twice... once for the initial request, and once for the continuation.
//  void cleanAndAssertTraces(
//    final int size,
//    @ClosureParams(value = SimpleType, options = "datadog.trace.agent.test.asserts.ListWriterAssert")
//    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST)
//    final Closure spec) {
//
//    // If this is failing, make sure HttpServerTestAdvice is applied correctly.
//    TEST_WRITER.waitForTraces(size * 3)
//    // TEST_WRITER is a CopyOnWriteArrayList, which doesn't support remove()
//    def toRemove = TEST_WRITER.findAll {
//      it.size() == 1 && it.get(0).operationName == "TEST_SPAN"
//    }
//    toRemove.each {
//      assertTrace(it, 1) {
//        basicSpan(it, 0, "TEST_SPAN", "ServerEntry")
//      }
//    }
//    assert toRemove.size() == size * 2
//    TEST_WRITER.removeAll(toRemove)
//
//    assertTraces(size, spec)
//  }
}
