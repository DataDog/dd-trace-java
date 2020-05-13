package datadog.trace.core


import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter

class SpanFactory {

  static DDSpan newSpanOf(long timestampMicro, String threadName = Thread.currentThread().name) {
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    def currentThreadName = Thread.currentThread().getName()
    Thread.currentThread().setName(threadName)
    def context = new DDSpanContext(
      1L,
      1L,
      0L,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      Collections.emptyMap(),
      PendingTrace.create(tracer, 1L),
      tracer, [:])
    Thread.currentThread().setName(currentThreadName)
    return DDSpan.create(timestampMicro, context)
  }

  static DDSpan newSpanOf(CoreTracer tracer) {
    def context = new DDSpanContext(
      1L,
      1L,
      0L,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      Collections.emptyMap(),
      PendingTrace.create(tracer, 1L),
      tracer, [:])
    return DDSpan.create(1, context)
  }

  static DDSpan newSpanOf(PendingTrace trace) {
    def context = new DDSpanContext(
      trace.traceId,
      1L,
      0L,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      Collections.emptyMap(),
      trace,
      trace.tracer, [:])
    return DDSpan.create(1, context)
  }

  static DDSpan newSpanOf(String serviceName, String envName) {
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    def context = new DDSpanContext(
      1L,
      1L,
      0L,
      serviceName,
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      Collections.emptyMap(),
      PendingTrace.create(tracer, 1L),
      tracer,
      [:])
    context.setTag("env", envName)
    return DDSpan.create(0l, context)
  }
}
