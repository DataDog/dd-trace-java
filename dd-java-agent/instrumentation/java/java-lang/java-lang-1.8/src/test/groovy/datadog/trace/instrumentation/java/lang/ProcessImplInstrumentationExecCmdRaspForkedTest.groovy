package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.gateway.CallbackProvider
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.java.lang.ProcessImplInstrumentationHelpers
import spock.lang.Shared

import java.util.function.BiFunction

import static datadog.trace.api.gateway.Events.EVENTS

class ProcessImplInstrumentationExecCmdRaspForkedTest extends  InstrumentationSpecification {

  @Shared
  protected static final ORIGINAL_TRACER = AgentTracer.get()

  protected traceSegment
  protected reqCtx
  protected span
  protected tracer

  void setup() {
    traceSegment = Stub(TraceSegment)
    reqCtx = Stub(RequestContext) {
      getTraceSegment() >> traceSegment
    }
    span = Stub(AgentSpan) {
      getRequestContext() >> reqCtx
    }
    tracer = Stub(AgentTracer.TracerAPI) {
      activeSpan() >> span
    }
    AgentTracer.forceRegister(tracer)
  }

  void cleanup() {
    AgentTracer.forceRegister(ORIGINAL_TRACER)
  }

  @Override
  protected void configurePreAgent() {
    injectSysConfig(AppSecConfig.APPSEC_ENABLED, 'true')
    injectSysConfig(AppSecConfig.APPSEC_RASP_ENABLED, 'true')
  }

  void 'test cmdiRaspCheck'() {

    setup:
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    final flow = Mock(Flow)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider

    when:
    ProcessImplInstrumentationHelpers.cmdiRaspCheck(['/bin/../usr/bin/reboot', '-f'] as String[])

    then:
    1 * callbackProvider.getCallback(EVENTS.execCmd()) >> listener
    1 * listener.apply(reqCtx, ['/bin/../usr/bin/reboot', '-f']) >> flow
  }
}
