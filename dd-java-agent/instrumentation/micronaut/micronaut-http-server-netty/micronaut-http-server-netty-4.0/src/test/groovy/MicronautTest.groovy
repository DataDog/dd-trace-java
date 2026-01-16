import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.debugger.DebuggerContext
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.micronaut.v4_0.MicronautDecorator
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator
import test.MicronautServer

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.ERROR
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.QUERY_ENCODED_BOTH
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS
import static datadog.trace.api.config.TraceInstrumentationConfig.CODE_ORIGIN_FOR_SPANS_ENABLED

class MicronautTest extends HttpServerTest<Object> {

  def codeOriginRecorder

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(CODE_ORIGIN_FOR_SPANS_ENABLED, "true")
    codeOriginRecorder = new DebuggerContext.CodeOriginRecorder() {
        def invoked = false
        @Override
        String captureCodeOrigin(boolean entry) {
          invoked = true
          return "done"
        }

        @Override
        String captureCodeOrigin(String typeName, String methodName, String descriptor, boolean entry) {
          invoked = true
          return "done"
        }
      }
    DebuggerContext.initCodeOrigin(codeOriginRecorder)
  }



  @Override
  HttpServer server() {
    return new MicronautServer()
  }

  @Override
  String component() {
    return NettyHttpServerDecorator.DECORATE.component()
  }

  @Override
  String expectedOperationName() {
    return "netty.request"
  }

  @Override
  boolean testExceptionBody() {
    false
  }

  @Override
  String testPathParam() {
    "/path/{id}/param"
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  boolean hasDecodedResource() {
    false
  }

  @Override
  Serializable expectedServerSpanRoute(ServerEndpoint endpoint) {
    switch (endpoint) {
      case NOT_FOUND:
        return null
      case PATH_PARAM:
        return testPathParam()
      case QUERY_ENCODED_BOTH:
        return endpoint.rawPath
      default:
        return endpoint.path
    }
  }

  @Override
  void handlerSpan(TraceAssert trace, ServerEndpoint endpoint = SUCCESS) {
    if (endpoint != NOT_FOUND) {
      assert codeOriginRecorder.invoked
    }
    trace.span {
      serviceName expectedServiceName()
      operationName "micronaut-controller"
      resourceName {
        it == "TestController.${endpoint.name().toLowerCase()}" || endpoint == NOT_FOUND && it == "404"
      }
      spanType DDSpanTypes.HTTP_SERVER
      errored (endpoint == EXCEPTION || endpoint == ERROR)
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" MicronautDecorator.DECORATE.component()
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.HTTP_STATUS" Integer
        if (endpoint == EXCEPTION) {
          errorTags(Exception, EXCEPTION.body)
        }
        defaultTags()
      }
    }
  }
}
