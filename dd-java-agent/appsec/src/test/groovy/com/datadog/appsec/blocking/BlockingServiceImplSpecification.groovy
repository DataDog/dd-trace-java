package com.datadog.appsec.blocking

import com.datadog.appsec.event.ChangeableFlow
import com.datadog.appsec.event.DataListener
import com.datadog.appsec.event.EventDispatcher
import com.datadog.appsec.event.EventProducerService
import com.datadog.appsec.event.OrderedCallback
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.gateway.GatewayContext
import datadog.appsec.api.blocking.BlockingContentType
import datadog.appsec.api.blocking.BlockingDetails
import datadog.trace.api.gateway.BlockResponseFunction
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.test.util.DDSpecification

class BlockingServiceImplSpecification extends DDSpecification {
  EventProducerService eps = new EventDispatcher()
  def bs = new BlockingServiceImpl(eps)
  def origTracer

  void setup() {
    origTracer = AgentTracer.get()
  }

  void cleanup() {
    AgentTracer.forceRegister origTracer
  }


  void shouldBlockUser() {
    setup:
    BlockingDetails result
    EventDispatcher.DataSubscriptionSet set = new EventDispatcher.DataSubscriptionSet()
    set.addSubscription([KnownAddresses.USER_ID], new DataListener() {
      final OrderedCallback.Priority priority = OrderedCallback.Priority.DEFAULT

      @Override
      void onDataAvailable(ChangeableFlow flow, AppSecRequestContext reqCtx, DataBundle dataBundle, GatewayContext gwCtx) {
        if (dataBundle.get(KnownAddresses.USER_ID) == 'blocked.user') {
          flow.action = new Flow.Action.RequestBlockingAction(405, BlockingContentType.HTML)
        }
      }
    })
    eps.subscribeDataAvailable set

    AppSecRequestContext appSecRequestContext = Stub()
    AgentTracer.forceRegister(Stub(AgentTracer.TracerAPI) {
      activeSpan() >> Stub(AgentSpan) {
        getRequestContext() >> Stub(RequestContext) {
          getData(RequestContextSlot.APPSEC) >> appSecRequestContext
        }
      }
    })

    when:
    result = bs.shouldBlockUser('blocked.user')

    then:
    0 * _
    result != null
    result.blockingContentType == BlockingContentType.HTML
    result.statusCode == 405

    when:
    result = bs.shouldBlockUser('not.blocked.user')

    then:
    0 * _
    result == null
  }

  void 'shouldBlockUser with no active span'() {
    setup:
    AgentTracer.TracerAPI tracerApi = Mock(AgentTracer.TracerAPI)
    AgentTracer.forceRegister(tracerApi)
    def result

    when:
    result = bs.shouldBlockUser('foo.bar')

    then:
    result == null
    1 * tracerApi.activeSpan() >> null
  }

  void tryCommitBlockingResponse() {
    setup:
    BlockResponseFunction brf = Mock()
    TraceSegment mts = Mock()
    AgentTracer.forceRegister(Stub(AgentTracer.TracerAPI) {
      activeSpan() >> Stub(AgentSpan) {
        getRequestContext() >> Stub(RequestContext) {
          getBlockResponseFunction() >> brf
          getTraceSegment() >> mts
        }
      }
    })

    when:
    boolean res = bs.tryCommitBlockingResponse(405, BlockingContentType.HTML, [:])

    then:
    res == true
    1 * brf.tryCommitBlockingResponse(mts, 405, BlockingContentType.HTML, [:], null) >> true
    1 * mts.effectivelyBlocked()
  }

  void 'tryCommitBlockingResponse without active span'() {
    setup:
    RequestContext reqCtx = Mock(RequestContext)
    AgentTracer.forceRegister(Stub(AgentTracer.TracerAPI) {
      activeSpan() >> Stub(AgentSpan) {
        getRequestContext() >> reqCtx
      }
    })

    when:
    boolean res = bs.tryCommitBlockingResponse(405, BlockingContentType.HTML, [:])

    then:
    res == false
    1 * reqCtx.getBlockResponseFunction() >> null
    noExceptionThrown()
  }

  void 'tryCommitBlockingResponse without blocking response function'() {
    setup:
    AgentTracer.TracerAPI tracerApi = Mock(AgentTracer.TracerAPI)
    AgentTracer.forceRegister(tracerApi)

    when:
    boolean res = bs.tryCommitBlockingResponse(405, BlockingContentType.HTML, [:])

    then:
    res == false
    1 * tracerApi.activeSpan() >> null
    noExceptionThrown()
  }
}
