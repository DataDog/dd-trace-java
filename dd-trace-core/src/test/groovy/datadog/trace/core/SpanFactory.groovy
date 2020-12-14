package datadog.trace.core

import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter

class SpanFactory {
  static final WRITER = new ListWriter()
  static final TRACER = CoreTracer.builder().writer(WRITER).build()

  static DDSpan newSpanOf(long timestampMicro, String threadName = Thread.currentThread().name) {
    def currentThreadName = Thread.currentThread().getName()
    Thread.currentThread().setName(threadName)
    def context = new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      TRACER.pendingTraceFactory.create(DDId.ONE),
      [:])
    Thread.currentThread().setName(currentThreadName)
    return DDSpan.create(timestampMicro, context)
  }

  static DDSpan newSpanOf(CoreTracer tracer) {
    def context = new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      tracer.pendingTraceFactory.create(DDId.ONE),
      [:])
    return DDSpan.create(0, context)
  }

  static DDSpan newSpanOf(CoreTracer tracer, ThreadLocal<Timer> timer) {
    def context = new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      tracer.pendingTraceFactory.create(DDId.ONE),
      [:])
    return DDSpan.create(0, context)
  }

  static DDSpan newSpanOf(PendingTrace trace) {
    def context = new DDSpanContext(
      trace.traceId,
      DDId.from(1),
      DDId.ZERO,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      trace,
      [:])
    return DDSpan.create(0, context)
  }

  static DDSpan newSpanOf(DDSpan parent) {
    def trace = parent.context().trace
    def context = new DDSpanContext(
      trace.traceId,
      DDId.from(2),
      parent.context().spanId,
      null,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      trace,
      [:])
    return DDSpan.create(0, context)
  }

  static DDSpan newSpanOf(String serviceName, String envName) {
    def context = new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      null,
      serviceName,
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      TRACER.pendingTraceFactory.create(DDId.ONE),
      [:])
    context.setTag("env", envName)
    return DDSpan.create(0l, context)
  }
}
