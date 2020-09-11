package datadog.trace.core

import datadog.trace.api.DDId
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.common.writer.ListWriter

class SpanFactory {

  static DDSpan newSpanOf(long timestampMicro, String threadName = Thread.currentThread().name) {
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    def currentThreadName = Thread.currentThread().getName()
    Thread.currentThread().setName(threadName)
    def context = new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      PendingTrace.create(tracer, DDId.ONE),
      tracer, [:])
    Thread.currentThread().setName(currentThreadName)
    return DDSpan.create(timestampMicro, context)
  }

  static DDSpan newSpanOf(CoreTracer tracer) {
    def context = new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      PendingTrace.create(tracer, DDId.ONE),
      tracer, [:])
    return DDSpan.create(1, context)
  }

  static DDSpan newSpanOf(CoreTracer tracer, ThreadLocal<Timer> timer) {
    def context = new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      PendingTrace.create(tracer, DDId.ONE),
      tracer, [:])
    return DDSpan.create(1, context)
  }

  static DDSpan newSpanOf(PendingTrace trace) {
    def context = new DDSpanContext(
      trace.traceId,
      DDId.from(1),
      DDId.ZERO,
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
      trace.tracer, [:])
    return DDSpan.create(1, context)
  }

  static DDSpan newSpanOf(String serviceName, String envName) {
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    def context = new DDSpanContext(
      DDId.from(1),
      DDId.from(1),
      DDId.ZERO,
      serviceName,
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      null,
      Collections.emptyMap(),
      false,
      "fakeType",
      0,
      PendingTrace.create(tracer, DDId.ONE),
      tracer,
      [:])
    context.setTag("env", envName)
    return DDSpan.create(0l, context)
  }
}
