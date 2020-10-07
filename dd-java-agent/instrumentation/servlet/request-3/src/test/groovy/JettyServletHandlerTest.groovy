import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.env.CapturedEnvironment
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.handler.ErrorHandler
import org.eclipse.jetty.servlet.ServletHandler

import javax.servlet.Servlet
import javax.servlet.http.HttpServletRequest

class JettyServletHandlerTest extends AbstractServlet3Test<Server, ServletHandler> {

  @Override
  Server startServer(int port) {
    Server server = new Server(port)
    ServletHandler handler = new ServletHandler()
    server.setHandler(handler)
    setupServlets(handler)
    server.addBean(new ErrorHandler() {
      protected void handleErrorPage(HttpServletRequest request, Writer writer, int code, String message) throws IOException {
        Throwable th = (Throwable) request.getAttribute("javax.servlet.error.exception")
        writer.write(th ? th.message : message)
      }
    })
    server.start()
    return server
  }

  @Override
  void addServlet(ServletHandler servletHandler, String path, Class<Servlet> servlet) {
    servletHandler.addServletWithMapping(servlet, path)
  }

  @Override
  void stopServer(Server server) {
    server.stop()
    server.destroy()
  }

  @Override
  String getContext() {
    ""
  }

  @Override
  Class<Servlet> servlet() {
    TestServlet3.Sync
  }

  @Override
  String expectedServiceName() {
    CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
  }

  @Override
  boolean testNotFound() {
    false
  }

  void cleanAndAssertTraces(
    final int size,
    @ClosureParams(value = SimpleType, options = "datadog.trace.agent.test.asserts.ListWriterAssert")
    @DelegatesTo(value = ListWriterAssert, strategy = Closure.DELEGATE_FIRST)
    final Closure spec) {

    // If this is failing, make sure HttpServerTestAdvice is applied correctly.
    TEST_WRITER.waitForTraces(size * 2)

    // Response instrumentation is broken with exceptions in jetty
    // Exceptions are handled outside of the servlet flow
    // Normally, the response spans would not be created because of the activeSpan() check
    // Since we artificially create TEST_SPAN, the response spans are created there
    // This removes the response spans added under TEST_SPAN

    def testTrace = TEST_WRITER.findAll {
      it.get(0).operationName.toString() == "TEST_SPAN"
    }
    testTrace[0].removeAll {
      it.operationName.toString() == "servlet.response"
    }

    super.cleanAndAssertTraces(size, spec)
  }
}
