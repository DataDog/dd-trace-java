package datadog.trace.instrumentation.java.lang

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.AppSecConfig
import datadog.trace.api.gateway.CallbackProvider
import static datadog.trace.api.gateway.Events.EVENTS
import datadog.trace.api.gateway.Flow
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.internal.TraceSegment
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import spock.lang.Shared

import java.util.function.BiFunction

class RuntimeInstrumentationForkedTest extends InstrumentationSpecification{

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

  void 'test shiRaspCheck'() {

    setup:
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    final flow = Mock(Flow)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider

    when:
    try {
      Runtime.getRuntime().exec(*args)
    }catch (Exception e){
      // ignore
    }

    then:
    cmdiExpected * callbackProvider.getCallback(EVENTS.execCmd()) >> listener
    shiExpected * callbackProvider.getCallback(EVENTS.shellCmd()) >> listener
    1 * listener.apply(reqCtx, args[0]) >> flow

    where:
    args                                                                     | cmdiExpected | shiExpected
    ['$(cat /etc/passwd 1>&2 ; echo .)']                                     | 0            | 1
    ['$(cat /etc/passwd 1>&2 ; echo .)', ['test'] as String[]]               | 0            | 1
    ['$(cat /etc/passwd 1>&2 ; echo .)', ['test'] as String[], new File('')] | 0            | 1
    [['/bin/../usr/bin/reboot', '-f'] as String[]]                                       | 1            | 0
    [['/bin/../usr/bin/reboot', '-f'] as String[], ['test'] as String[]]                 | 1            | 0
    [['/bin/../usr/bin/reboot', '-f'] as String[], ['test'] as String[], new File('')]   | 1            | 0
  }

  void 'test shiCheck reset'() {

    setup:
    final callbackProvider = Mock(CallbackProvider)
    final listener = Mock(BiFunction)
    final flow = Mock(Flow)
    tracer.getCallbackProvider(RequestContextSlot.APPSEC) >> callbackProvider

    when:
    try {
      Runtime.getRuntime().exec('$(cat /etc/passwd 1>&2 ; echo .)')
    }catch (Exception e){
      // ignore
    }

    then:
    0 * callbackProvider.getCallback(EVENTS.execCmd()) >> listener
    1 * callbackProvider.getCallback(EVENTS.shellCmd()) >> listener
    1 * listener.apply(reqCtx, _) >> flow

    when:
    try {
      Runtime.getRuntime().exec(['/bin/../usr/bin/reboot', '-f'] as String[])
    }catch (Exception e){
      // ignore
    }

    then:
    1 * callbackProvider.getCallback(EVENTS.execCmd()) >> listener
    0 * callbackProvider.getCallback(EVENTS.shellCmd()) >> listener
    1 * listener.apply(reqCtx, _) >> flow
  }
}
