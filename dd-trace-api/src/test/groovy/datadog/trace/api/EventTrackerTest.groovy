package datadog.trace.api

import datadog.trace.api.experimental.DataStreamsCheckpointer

import datadog.trace.api.interceptor.TraceInterceptor
import datadog.trace.api.internal.InternalTracer
import datadog.trace.api.internal.TraceSegment
import datadog.trace.api.profiling.Profiling
import datadog.trace.context.TraceScope
import datadog.trace.test.util.DDSpecification

class EventTrackerTest extends DDSpecification {

  def traceSegment = Mock(TraceSegment)

  @Override
  void setup() {
    def tracer = new TracerAPI(traceSegment)
    GlobalTracer.forceRegister(tracer)
  }

  @Override
  void cleanup() {
    GlobalTracer.eventTracker = EventTracker.NO_EVENT_TRACKER
    GlobalTracer.provider = null
  }

  def "test track login success event"() {
    when:
    GlobalTracer.getEventTracker().trackLoginSuccessEvent('user1', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('appsec.events.users.login.success.track', true)
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.success.sdk', true)
    1 * traceSegment.setTagTop('usr.id', 'user1')
    1 * traceSegment.setTagTop('manual.keep', true)
    1 * traceSegment.setTagTop('appsec.events.users.login.success', ['key1':'value1', 'key2':'value2'])
    0 * _
  }

  def "test track login failure event"() {
    when:
    GlobalTracer.getEventTracker().trackLoginFailureEvent('user1',true, ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.track', true)
    1 * traceSegment.setTagTop('_dd.appsec.events.users.login.failure.sdk', true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.id', 'user1')
    1 * traceSegment.setTagTop('appsec.events.users.login.failure.usr.exists', true)
    1 * traceSegment.setTagTop('manual.keep', true)
    1 * traceSegment.setTagTop('appsec.events.users.login.failure', ['key1':'value1', 'key2':'value2'])
    0 * _
  }

  def "test track custom event"() {
    when:
    GlobalTracer.getEventTracker().trackCustomEvent('myevent', ['key1': 'value1', 'key2': 'value2'])

    then:
    1 * traceSegment.setTagTop('appsec.events.myevent.track', true, true)
    1 * traceSegment.setTagTop('_dd.appsec.events.myevent.sdk', true)
    1 * traceSegment.setTagTop('manual.keep', true)
    1 * traceSegment.setTagTop('appsec.events.myevent', ['key1':'value1', 'key2':'value2'], true)
    0 * _
  }

  def "test wrong event argument validation"() {
    when:
    GlobalTracer.getEventTracker().trackLoginSuccessEvent(null, null)

    then:
    thrown IllegalArgumentException

    when:
    GlobalTracer.getEventTracker().trackLoginFailureEvent(null, false, null)

    then:
    thrown IllegalArgumentException

    when:
    GlobalTracer.getEventTracker().trackCustomEvent(null, null)

    then:
    thrown IllegalArgumentException

    when:
    GlobalTracer.getEventTracker().trackLoginSuccessEvent('', null)

    then:
    thrown IllegalArgumentException

    when:
    GlobalTracer.getEventTracker().trackLoginFailureEvent('', false, null)

    then:
    thrown IllegalArgumentException

    when:
    GlobalTracer.getEventTracker().trackCustomEvent('', null)

    then:
    thrown IllegalArgumentException
  }

  def "test traceSegment is null"() {
    setup:
    def tracer = new TracerAPI(null)
    GlobalTracer.forceRegister(tracer)

    when:
    GlobalTracer.getEventTracker().trackLoginSuccessEvent('user1', null)
    GlobalTracer.getEventTracker().trackLoginFailureEvent('user1', false, null)
    GlobalTracer.getEventTracker().trackCustomEvent('myevent', null)

    then:
    0 * _
  }

  def "test no tracer"() {
    setup:
    GlobalTracer.provider = null

    when:
    GlobalTracer.getEventTracker().trackLoginSuccessEvent('user1', null)
    GlobalTracer.getEventTracker().trackLoginFailureEvent('user1', false, null)
    GlobalTracer.getEventTracker().trackCustomEvent('myevent', null)

    then:
    0 * _
  }

  /**
   * Helper class
   */
  class TracerAPI implements InternalTracer, Tracer {

    private final TraceSegment traceSegment

    TracerAPI(TraceSegment traceSegment) {
      this.traceSegment = traceSegment
    }

    @Override
    String getTraceId() {
      return null
    }

    @Override
    String getSpanId() {
      return null
    }

    @Override
    boolean addTraceInterceptor(TraceInterceptor traceInterceptor) {
      return false
    }

    @Override
    TraceScope muteTracing() {
      return null
    }

    @Override
    DataStreamsCheckpointer getDataStreamsCheckpointer() {
      return null
    }

    @Override
    void addScopeListener(Runnable afterScopeActivatedCallback, Runnable afterScopeClosedCallback) {
    }

    @Override
    void flush() {
    }

    @Override
    void flushMetrics() {
    }

    @Override
    Profiling getProfilingContext() {
      return null
    }

    @Override
    TraceSegment getTraceSegment() {
      return this.traceSegment
    }
  }
}
