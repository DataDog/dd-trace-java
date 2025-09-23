package datadog.trace.instrumentation.play25.server

import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.agent.test.base.HttpServer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.play25.PlayController
import datadog.trace.test.util.Flaky
import groovy.transform.CompileStatic
import scala.concurrent.ExecutionContext$
import spock.lang.Shared

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.CUSTOM_EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.EXCEPTION
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.FORWARDED
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.NOT_FOUND
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.PATH_PARAM
import static datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint.SUCCESS

class PlayScalaRoutesServerTest extends PlayServerTest {
  @Shared
  ExecutorService executor

  def cleanupSpec() {
    executor.shutdown()
  }

  @Override
  protected void configurePreAgent() {
    super.configurePreAgent()
    injectSysConfig(TraceInstrumentationConfig.HTTP_SERVER_ROUTE_BASED_NAMING, 'false')
  }

  @Override
  @CompileStatic
  HttpServer server() {
    executor = Executors.newCachedThreadPool()

    def router = new router.Routes(
      new play.core.j.JavaHttpErrorHandlerAdapter(TestHttpErrorHandler.INSTANCE),
      new PlayController(ExecutionContext$.MODULE$.fromExecutor(executor))
      )
    new PlayHttpServer(router.asJava())
  }

  @Override
  String testPathParam() {
    '/path/?/param'
  }

  @Flaky("https://github.com/DataDog/dd-trace-java/issues/6952")
  @Override
  boolean testUserBlocking() {
    "false" != System.getProperty("run.flaky.tests") // Set when using -PskipFlakyTests gradle parameter
  }

  @Override
  Map<String, ?> expectedIGPathParams() {
    [path: '123']
  }

  private String routeFor(HttpServerTest.ServerEndpoint endpoint) {
    switch (endpoint) {
      case PATH_PARAM:
        return '/path/$path<[^/]+>/param'
      case NOT_FOUND:
        return null
      default:
        endpoint.rawPath
    }
  }

  @Override
  Map<String, Serializable> expectedExtraServerTags(HttpServerTest.ServerEndpoint endpoint) {
    [(Tags.HTTP_ROUTE): routeFor(endpoint)]
  }

  @Override
  boolean hasHandlerSpan() {
    true
  }

  @Override
  void handlerSpan(TraceAssert trace, HttpServerTest.ServerEndpoint endpoint = SUCCESS) {
    def expectedQueryTag = expectedQueryTag(endpoint)
    trace.span {
      serviceName expectedServiceName()
      operationName 'play.request'
      spanType DDSpanTypes.HTTP_SERVER
      errored endpoint == EXCEPTION || endpoint == CUSTOM_EXCEPTION
      childOfPrevious()
      tags {
        "$Tags.COMPONENT" 'play-action'
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_SERVER
        "$Tags.PEER_HOST_IPV4" (endpoint == FORWARDED ? endpoint.body : '127.0.0.1')
        "$Tags.HTTP_CLIENT_IP" (endpoint == FORWARDED ? endpoint.body : '127.0.0.1')
        "$Tags.HTTP_URL" String
        "$Tags.HTTP_HOSTNAME" address.host
        "$Tags.HTTP_METHOD" String
        "$Tags.HTTP_ROUTE" this.routeFor(endpoint)
        if (endpoint == EXCEPTION || endpoint == CUSTOM_EXCEPTION) {
          errorTags(endpoint == CUSTOM_EXCEPTION ? TestHttpErrorHandler.CustomRuntimeException : RuntimeException, endpoint.body)
        }
        if (endpoint.query) {
          "$DDTags.HTTP_QUERY" expectedQueryTag
        }
        defaultTags()
      }
    }
  }
}
