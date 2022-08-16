import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.DatadogClassLoader
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.instrumentation.http_url_connection.UrlInstrumentation

import static datadog.trace.agent.test.utils.PortUtils.UNUSABLE_PORT
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.config.TraceInstrumentationConfig.HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope
import static datadog.trace.bootstrap.instrumentation.httpurlconnection.HttpUrlState.OPERATION_NAME

class UrlConnectionTest extends AgentTestRunner {

  def "trace request with connection failure #scheme"() {
    when:
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService")
    runUnderTrace("someTrace") {
      URLConnection connection = url.openConnection()
      connection.setConnectTimeout(10000)
      connection.setReadTimeout(10000)
      assert activeScope() != null
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
          operationName OPERATION_NAME
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

  def "trace request with connection failure to a local file with broken url path"() {
    setup:
    def url = new URI("file:/some-random-file%abc").toURL()

    when:
    injectSysConfig(HTTP_CLIENT_HOST_SPLIT_BY_DOMAIN, "$renameService")
    runUnderTrace("someTrace") {
      url.openConnection()
    }

    then:
    thrown IllegalArgumentException

    expect:
    assertTraces(1) {
      trace(2) {
        span {
          operationName "someTrace"
          parent()
          errored true
          tags {
            errorTags IllegalArgumentException, String
            defaultTags()
          }
        }
        span {
          operationName "file.request"
          resourceName "$url.path"
          spanType DDSpanTypes.HTTP_CLIENT
          childOf span(0)
          errored true
          tags {
            "$Tags.COMPONENT" UrlInstrumentation.COMPONENT
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
            // FIXME: These tags really make no sense for non-http connections, why do we set them?
            "$Tags.HTTP_URL" "$url"
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
}
