package datadog.trace.agent.decorator

import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.util.test.DDSpecification
import io.opentracing.tag.Tags
import spock.lang.Shared
import spock.lang.Timeout

@Timeout(20)
// onPeerConnection might be slow...
class BaseDecoratorTest extends DDSpecification {

  @Shared
  def decorator = newDecorator()

  def span = Mock(AgentSpan)

  def "test afterStart"() {
    when:
    decorator.afterStart(span)

    then:
    1 * span.setTag(DDTags.SPAN_TYPE, decorator.spanType())
    1 * span.setTag(Tags.COMPONENT.key, "test-component")
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    0 * _
  }

  def "test onPeerConnection"() {
    when:
    decorator.onPeerConnection(span, connection)

    then:
    if (connection.getAddress()) {
      2 * span.setTag(Tags.PEER_HOSTNAME.key, connection.hostName)
    } else {
      1 * span.setTag(Tags.PEER_HOSTNAME.key, connection.hostName)
    }
    1 * span.setTag(Tags.PEER_PORT.key, connection.port)
    if (connection.address instanceof Inet4Address) {
      1 * span.setTag(Tags.PEER_HOST_IPV4.key, connection.address.hostAddress)
    }
    if (connection.address instanceof Inet6Address) {
      1 * span.setTag(Tags.PEER_HOST_IPV6.key, connection.address.hostAddress)
    }
    0 * _

    where:
    connection                                      | _
    new InetSocketAddress("localhost", 888)         | _
    new InetSocketAddress("ipv6.google.com", 999)   | _
    new InetSocketAddress("bad.address.local", 999) | _
  }

  def "test onError"() {
    when:
    decorator.onError(span, error)

    then:
    if (error) {
      1 * span.setError(true)
      1 * span.addThrowable(error)
    }
    0 * _

    where:
    error << [new Exception(), null]
  }

  def "test beforeFinish"() {
    when:
    decorator.beforeFinish(span)

    then:
    0 * _
  }

  def "test assert null span"() {
    when:
    decorator.afterStart((AgentSpan) null)

    then:
    thrown(AssertionError)

    when:
    decorator.onError((AgentSpan) null, null)

    then:
    thrown(AssertionError)

    when:
    decorator.onError((AgentSpan) null, null)

    then:
    thrown(AssertionError)

    when:
    decorator.onPeerConnection((AgentSpan) null, null)

    then:
    thrown(AssertionError)
  }

  def "test assert null scope"() {
    when:
    decorator.onError((AgentScope) null, null)

    then:
    thrown(AssertionError)

    when:
    decorator.onError((AgentScope) null, null)

    then:
    thrown(AssertionError)

    when:
    decorator.beforeFinish((AgentScope) null)

    then:
    thrown(AssertionError)
  }

  def "test assert non-null scope"() {
    setup:
    def span = Mock(AgentSpan)
    def scope = Mock(AgentScope)

    when:
    decorator.onError(scope, null)

    then:
    1 * scope.span() >> span

    when:
    decorator.beforeFinish(scope)

    then:
    1 * scope.span() >> span
  }

  def "test analytics rate default disabled"() {
    when:
    BaseDecorator dec = newDecorator(defaultEnabled, hasConfigNames)

    then:
    dec.traceAnalyticsEnabled == defaultEnabled
    dec.traceAnalyticsSampleRate == sampleRate.floatValue()

    where:
    defaultEnabled | hasConfigNames | sampleRate
    true           | false          | 1.0
    false          | false          | 1.0
    false          | true           | 1.0
  }

  def "test analytics rate enabled:#enabled, integration:#integName, sampleRate:#sampleRate"() {
    setup:
    ConfigUtils.updateConfig {
      System.properties.setProperty("dd.${integName}.analytics.enabled", "true")
      System.properties.setProperty("dd.${integName}.analytics.sample-rate", "$sampleRate")
    }

    when:
    BaseDecorator dec = newDecorator(enabled)

    then:
    dec.traceAnalyticsEnabled == expectedEnabled
    dec.traceAnalyticsSampleRate == (Float) expectedRate

    cleanup:
    System.clearProperty("dd.${integName}.analytics.enabled")
    System.clearProperty("dd.${integName}.analytics.sample-rate")

    where:
    enabled | integName | sampleRate | expectedEnabled | expectedRate
    false   | ""        | ""         | false           | 1.0
    true    | ""        | ""         | true            | 1.0
    false   | "test1"   | 0.5        | true            | 0.5
    false   | "test2"   | 0.75       | true            | 0.75
    true    | "test1"   | 0.2        | true            | 0.2
    true    | "test2"   | 0.4        | true            | 0.4
    true    | "test1"   | ""         | true            | 1.0
    true    | "test2"   | ""         | true            | 1.0
  }

  def "test spanNameForMethod"() {
    when:
    def result = decorator.spanNameForMethod(method)

    then:
    result == "${name}.run"

    where:
    target                         | name
    SomeInnerClass                 | "SomeInnerClass"
    SomeNestedClass                | "SomeNestedClass"
    SampleJavaClass.anonymousClass | "SampleJavaClass\$1"

    method = target.getDeclaredMethod("run")
  }

  def newDecorator() {
    return newDecorator(false)
  }

  def newDecorator(boolean analyticsEnabledDefault, boolean emptyInstrumentationNames = false) {
    return emptyInstrumentationNames ?
      new BaseDecorator() {
        @Override
        protected String[] instrumentationNames() {
          return []
        }

        @Override
        protected String spanType() {
          return "test-type"
        }

        @Override
        protected String component() {
          return "test-component"
        }

        protected boolean traceAnalyticsDefault() {
          return true
        }
      } :
      analyticsEnabledDefault ?
        new BaseDecorator() {
          @Override
          protected String[] instrumentationNames() {
            return ["test1", "test2"]
          }

          @Override
          protected String spanType() {
            return "test-type"
          }

          @Override
          protected String component() {
            return "test-component"
          }

          protected boolean traceAnalyticsDefault() {
            return true
          }
        } :
        new BaseDecorator() {
          @Override
          protected String[] instrumentationNames() {
            return ["test1", "test2"]
          }

          @Override
          protected String spanType() {
            return "test-type"
          }

          @Override
          protected String component() {
            return "test-component"
          }
        }
  }

  class SomeInnerClass implements Runnable {
    void run() {
    }
  }

  static class SomeNestedClass implements Runnable {
    void run() {
    }
  }
}
