package datadog.trace.core.postprocessor

import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.core.DDSpan
import datadog.trace.core.DDSpanContext
import datadog.trace.core.PendingTrace
import datadog.trace.test.util.DDSpecification

import java.util.function.BiConsumer
import java.util.function.BooleanSupplier

import static datadog.trace.api.gateway.Events.EVENTS


class AppSecSpanPostProcessorTest extends DDSpecification {
  def "process returns false if span context is null"() {
    given:
    def processor = new AppSecSpanPostProcessor()
    def span = Mock(DDSpan)
    def timeoutCheck = Mock(BooleanSupplier)
    (span.context()) >> null

    expect:
    !processor.process(span, timeoutCheck)
  }

  def "process returns false if callback provider is null"() {
    given:
    AgentTracer.TracerAPI tracer = Mock(AgentTracer.TracerAPI)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> null
    def processor = new AppSecSpanPostProcessor() {
        @Override
        protected AgentTracer.TracerAPI tracer() {
          return tracer
        }
      }
    def span = Mock(DDSpan) {
      context() >> Mock(DDSpanContext)
    }
    def timeoutCheck = Mock(BooleanSupplier)

    expect:
    !processor.process(span, timeoutCheck)
  }

  def "process returns false if request context is null"() {
    given:
    AgentTracer.TracerAPI tracer = Mock(AgentTracer.TracerAPI)
    def cbp = Mock(CallbackProvider)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> cbp
    def processor = new AppSecSpanPostProcessor() {
        @Override
        protected AgentTracer.TracerAPI tracer() {
          return tracer
        }
      }
    def span = Mock(DDSpan) {
      context() >> Mock(DDSpanContext)
      getRequestContext() >> null
    }
    def timeoutCheck = Mock(BooleanSupplier)

    expect:
    !processor.process(span, timeoutCheck)
  }

  def "process returns false if post-processing callback is null"() {
    given:
    AgentTracer.TracerAPI tracer = Mock(AgentTracer.TracerAPI)
    def cbp = Mock(CallbackProvider)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> cbp
    cbp.getCallback(EVENTS.postProcessing()) >> null
    def processor = new AppSecSpanPostProcessor() {
        @Override
        protected AgentTracer.TracerAPI tracer() {
          return tracer
        }
      }
    def span = Mock(DDSpan) {
      context() >> Mock(DDSpanContext)
      getRequestContext() >> Mock(RequestContext)
    }
    def timeoutCheck = Mock(BooleanSupplier)

    expect:
    !processor.process(span, timeoutCheck)
  }

  def "process returns true when all components are properly configured"() {
    given:
    def callback = Mock(BiConsumer)
    AgentTracer.TracerAPI tracer = Mock(AgentTracer.TracerAPI)
    def cbp = Mock(CallbackProvider)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> cbp
    cbp.getCallback(EVENTS.postProcessing()) >> callback
    def processor = new AppSecSpanPostProcessor() {
        @Override
        protected AgentTracer.TracerAPI tracer() {
          return tracer
        }
      }
    def span = DDSpan.create("test", 0, Mock(DDSpanContext) {
      isRequiresPostProcessing() >> true
      getTrace() >> Mock(PendingTrace) {
        getCurrentTimeNano() >> 0
      }
      getRequestContext() >> Mock(RequestContext)
    }, [])
    def timeoutCheck = Mock(BooleanSupplier)

    when:
    boolean result = processor.process(span, timeoutCheck)

    then:
    result
    1 * callback.accept(_, _)
  }
}

