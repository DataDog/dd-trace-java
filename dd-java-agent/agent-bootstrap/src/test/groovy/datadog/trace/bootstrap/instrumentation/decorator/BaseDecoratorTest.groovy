package datadog.trace.bootstrap.instrumentation.decorator


import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.test.util.DDSpecification
import spock.lang.Shared

class BaseDecoratorTest extends DDSpecification {

  @Shared
  def decorator = newDecorator()

  @Shared
  def errorPriority = null as Byte

  def span = Mock(AgentSpan)
  def spanContext = Mock(AgentSpanContext)

  def "test afterStart"() {
    when:
    decorator.afterStart(span)

    then:
    1 * span.setSpanType(decorator.spanType())
    1 * span.setTag(Tags.COMPONENT, "test-component")
    1 * span.context() >> spanContext
    1 * spanContext.setIntegrationName("test-component")
    _ * span.setTag(_, _) // Want to allow other calls from child implementations.
    _ * span.setMeasured(true)
    _ * span.setMetric(_, _)
    _ * span.setServiceName(_)
    _ * span.setOperationName(_)
    _ * span.setSamplingPriority(_)
    0 * _
  }

  def "test onPeerConnection"() {
    when:
    decorator.onPeerConnection(span, connection)

    then:
    if (!connection.isUnresolved()) {
      1 * span.setTag(Tags.PEER_HOSTNAME, connection.hostName)
    }
    1 * span.setTag(Tags.PEER_PORT, connection.port)
    if (connection.address instanceof Inet4Address) {
      1 * span.setTag(Tags.PEER_HOST_IPV4, connection.address.hostAddress)
    }
    if (connection.address instanceof Inet6Address) {
      1 * span.setTag(Tags.PEER_HOST_IPV6, connection.address.hostAddress)
    }
    0 * _

    where:
    connection                                                   | _
    new InetSocketAddress("localhost", 888)                      | _
    new InetSocketAddress("ipv6.google.com", 999)                | _
    InetSocketAddress.createUnresolved("bad.address.local", 999) | _
  }

  def "test onError"() {
    when:
    decorator.onError(span, error)

    then:
    if (error) {
      1 * span.addThrowable(error, errorPriority != null ? errorPriority : ErrorPriorities.DEFAULT)
    }
    0 * _

    where:
    error << [new Exception(), null]
  }

  def "test beforeFinish"() {
    when:
    decorator.beforeFinish(span)

    then:
    (0..1) * span.getLocalRootSpan()
    0 * _
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
    injectSysConfig("dd.${integName}.analytics.enabled", "true")
    injectSysConfig("dd.${integName}.analytics.sample-rate", "$sampleRate")

    when:
    BaseDecorator dec = newDecorator(enabled)

    then:
    dec.traceAnalyticsEnabled == expectedEnabled
    dec.traceAnalyticsSampleRate == (Float) expectedRate

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
    result.toString() == "${name}.run"

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
        protected CharSequence spanType() {
          return "test-type"
        }

        @Override
        protected CharSequence component() {
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
        protected CharSequence spanType() {
          return "test-type"
        }

        @Override
        protected CharSequence component() {
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
        protected CharSequence spanType() {
          return "test-type"
        }

        @Override
        protected CharSequence component() {
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
