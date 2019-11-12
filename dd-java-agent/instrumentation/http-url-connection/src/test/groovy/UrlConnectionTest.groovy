import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.Config
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.DatadogClassLoader
import datadog.trace.instrumentation.api.Tags
import datadog.trace.instrumentation.http_url_connection.UrlInstrumentation

import static datadog.trace.agent.test.utils.ConfigUtils.withConfigOverride
import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.instrumentation.api.AgentTracer.activeScope
import static datadog.trace.instrumentation.http_url_connection.HttpUrlConnectionInstrumentation.HttpUrlState.OPERATION_NAME

class UrlConnectionTest extends AgentTestRunner {

  def "trace request with connection failure #scheme"() {
    when:
    withConfigOverride(Config.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService") {
      runUnderTrace("someTrace") {
        URLConnection connection = url.openConnection()
        connection.setConnectTimeout(10000)
        connection.setReadTimeout(10000)
        assert activeScope() != null
        connection.inputStream
      }
    }

    then:
    thrown ConnectException

    expect:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          operationName "someTrace"
          parent()
          errored true
          tags {
            errorTags ConnectException, String
            defaultTags()
          }
        }
        span(1) {
          serviceName renameService ? "localhost" : "unnamed-java-app"
          operationName OPERATION_NAME
          resourceName "GET /"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          errored true
          tags {
            "$Tags.COMPONENT" "http-url-connection"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            "$Tags.HTTP_URL" "$url/"
            "$Tags.HTTP_METHOD" "GET"
            "$Tags.PEER_HOSTNAME" "localhost"
            "$Tags.PEER_PORT" UNUSABLE_PORT
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

  def "trace request with connection failure to a local file with broken url path"() {
    setup:
    def url = new URI("file:/some-random-file%abc").toURL()

    when:
    withConfigOverride(Config.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService") {
      runUnderTrace("someTrace") {
        url.openConnection()
      }
    }

    then:
    thrown IllegalArgumentException

    expect:
    assertTraces(1) {
      trace(0, 2) {
        span(0) {
          operationName "someTrace"
          parent()
          errored true
          tags {
            errorTags IllegalArgumentException, String
            defaultTags()
          }
        }
        span(1) {
          serviceName "unnamed-java-app"
          operationName "file.request"
          resourceName "file:$url.path"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          errored true
          tags {
            "$Tags.COMPONENT" UrlInstrumentation.COMPONENT
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            // FIXME: These tags really make no sense for non-http connections, why do we set them?
            "$Tags.HTTP_URL" "$url"
            "$Tags.PEER_PORT" 80
            errorTags IllegalArgumentException, String
            defaultTags()
          }
        }
      }
    }

    where:
    renameService << [false, true]
  }

  def "DatadogClassloader ClassNotFoundException doesn't create span"() {
    given:
    ClassLoader datadogLoader = new DatadogClassLoader(null, null, null)
    ClassLoader childLoader = new URLClassLoader(new URL[0], datadogLoader)

    when:
    runUnderTrace("someTrace") {
      childLoader.loadClass("datadog.doesnotexist")
    }

    then:
    thrown ClassNotFoundException

    expect:
    assertTraces(1) {
      trace(0, 1) {
        span(0) {
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
}
