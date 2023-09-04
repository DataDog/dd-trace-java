package com.datadog.appsec.gateway

import com.datadog.appsec.AppSecSystem
import com.datadog.appsec.config.TraceSegmentPostProcessor
import com.datadog.appsec.event.EventDispatcher
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.EventType
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.event.data.SingletonDataBundle
import com.datadog.appsec.report.AppSecEventWrapper
import com.datadog.appsec.report.raw.events.AppSecEvent100
import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.function.TriConsumer
import datadog.trace.api.function.TriFunction
import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.gateway.SubscriptionService
import datadog.trace.api.http.StoredBodySupplier
import datadog.trace.api.time.TimeSource
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase
import datadog.trace.test.util.DDSpecification

import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.api.gateway.Events.EVENTS

class RequestStartedCallbackSpecification extends DDSpecification {
  def setup() {
    AppSecSystem.active = true
  }

  def cleanup() {
    AppSecSystem.active = false
  }

  void 'produces appsec context and publishes event'() {
    given:
    EventProducerService eventProducer = Mock()
    final cb = new RequestStartedCallback(eventProducer)

    when:
    def startFlow = cb.get()

    then:
    1 * eventProducer.publishEvent(_ as AppSecRequestContext, EventType.REQUEST_START)
    0 * _
    def producedCtx = startFlow.getResult()
    producedCtx instanceof AppSecRequestContext
    startFlow.action == Flow.Action.Noop.INSTANCE
  }

  void 'returns null context if appsec is disabled'() {
    given:
    EventProducerService eventProducer = Mock()
    final cb = new RequestStartedCallback(eventProducer)
    AppSecSystem.active = false

    when:
    def startFlow = cb.get()

    then:
    0 * _
    Object producedCtx = startFlow.getResult()
    producedCtx == null

    cleanup:
    AppSecSystem.active = true
  }
}
