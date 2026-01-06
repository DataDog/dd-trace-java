import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan

import datadog.trace.agent.test.naming.VersionedNamingTestBase
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.DatadogClassLoader
import datadog.trace.bootstrap.instrumentation.api.Tags

abstract class UrlConnectionTest extends VersionedNamingTestBase {

  def "trace request with connection failure #scheme"() {
    when:
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService")
    runUnderTrace("someTrace") {
      URLConnection connection = url.openConnection()
      connection.setConnectTimeout(10000)
      connection.setReadTimeout(10000)
      assert activeSpan() != null
      connection.inputStream
    }

    then:
    thrown ConnectException

    expect:
    assertTraces(1) {
      trace(2) {
        span {
          operationName "someTrace"
          parent()
          errored true
          tags {
            errorTags ConnectException, String
            defaultTags()
          }
        }
        span {
          if (renameService) {
            serviceName "localhost"
          }
          operationName operation("http")
          resourceName "GET /"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          errored true
          tags {
            "$Tags.COMPONENT" "http-url-connection"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" UNUSABLE_PORT
            "$Tags.HTTP_URL" "$url/"
            "$Tags.HTTP_METHOD" "GET"
            errorTags ConnectException, String
            defaultTags()
          }
        }
      }
    }

    where:
    scheme  | renameService
    "http"  | true
    "https" | false

    url = new URI("$scheme://localhost:$UNUSABLE_PORT").toURL()
  }

  def "DatadogClassloader ClassNotFoundException doesn't create span"() {
    given:
    ClassLoader datadogLoader = new DatadogClassLoader()
    ClassLoader childLoader = new URLClassLoader(new URL[0], datadogLoader)

    when:
    runUnderTrace("someTrace") {
      childLoader.loadClass("datadog.doesnotexist")
    }

    then:
    thrown ClassNotFoundException

    expect:
    assertTraces(1) {
      trace(1) {
        span {
          operationName "someTrace"
          parent()
          errored true
          tags {
            errorTags ClassNotFoundException, String
            defaultTags()
          }
        }
      }
    }
  }

  @Override
  final String service() {
    return null
  }

  @Override
  final String operation() {
    return null
  }

  abstract String operation(String protocol)
}

class UrlConnectionV0ForkedTest extends UrlConnectionTest {

  @Override
  int version() {
    return 0
  }

  @Override
  String operation(String protocol) {
    return "${protocol}.request"
  }
}

class UrlConnectionV1ForkedTest extends UrlConnectionTest {

  @Override
  int version() {
    return 1
  }

  @Override
  String operation(String protocol) {
    return "${protocol}.client.request"
  }
}
