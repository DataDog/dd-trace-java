package datadog.trace.bootstrap.instrumentation.decorator

import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString

class UrlConnectionDecoratorTest extends ClientDecoratorTest {
  def "test url handling for #url"() {
    setup:
    def decorator = newDecorator()

    when:
    decorator.onURI(span, new URI(url))

    then:
    if (expectedUrl) {
      1 * span.setTag(Tags.HTTP_URL, expectedUrl)
    }
    if (hostname) {
      1 * span.setTag(Tags.PEER_HOSTNAME, hostname)
    }
    if (port) {
      1 * span.setTag(Tags.PEER_PORT, port)
    }
    0 * _

    where:
    url                                  | expectedUrl                          | hostname | port
    "https://host:0"                     | "https://host:0"                     | "host"   | null
    "https://host/path"                  | "https://host/path"                  | "host"   | null
    "http://host:99/path?query#fragment" | "http://host:99/path?query#fragment" | "host"   | 99
  }

  def "test operation name for protocol #protocol"() {
    setup:
    def decorator = newDecorator()

    when:
    def operationName = decorator.operationName(protocol)

    then:
    operationName instanceof UTF8BytesString
    operationName.toString() == expectedOperationName

    where:
    protocol  | expectedOperationName
    "http"    | "http.request"
    "ftp"     | "ftp.request"
    "file"    | "file.request"
  }

  @Override
  def newDecorator() {
    return new UrlConnectionDecorator() {
        @Override
        protected String[] instrumentationNames() {
          return ["test1", "test2"]
        }

        @Override
        protected CharSequence spanType() {
          return DDSpanTypes.TEST
        }

        @Override
        protected String service() {
          return null
        }

        @Override
        protected CharSequence component() {
          return "test-component"
        }

        protected boolean traceAnalyticsDefault() {
          return true
        }
      }
  }
}
