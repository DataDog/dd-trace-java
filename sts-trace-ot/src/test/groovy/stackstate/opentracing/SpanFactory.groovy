package stackstate.opentracing

import stackstate.trace.common.sampling.PrioritySampling
import stackstate.trace.common.writer.ListWriter

class SpanFactory {
  static newSpanOf(long timestampMicro) {
    def writer = new ListWriter()
    def tracer = new STSTracer(writer)
    def fakePidProvider = [getPid: {-> return (Long)42}] as ISTSSpanContextPidProvider
    def fakeHostNameProvider = [getHostName: {-> return "fakehost"}] as ISTSSpanContextHostNameProvider
    def context = new STSSpanContext(
      1L,
      1L,
      0L,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      Collections.emptyMap(),
      false,
      "fakeType",
      Collections.emptyMap(),
      new PendingTrace(tracer, 1L),
      tracer)
    context.setPidProvider(fakePidProvider)
    context.setHostNameProvider(fakeHostNameProvider)
    return new STSSpan(timestampMicro, context)
  }

  static newSpanOf(STSTracer tracer) {
    def fakePidProvider = [getPid: {-> return (Long)42}] as ISTSSpanContextPidProvider
    def fakeHostNameProvider = [getHostName: {-> return "fakehost"}] as ISTSSpanContextHostNameProvider
    def context = new STSSpanContext(
      1L,
      1L,
      0L,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      Collections.emptyMap(),
      false,
      "fakeType",
      Collections.emptyMap(),
      new PendingTrace(tracer, 1L),
      tracer)
    context.setPidProvider(fakePidProvider)
    context.setHostNameProvider(fakeHostNameProvider)
    return new STSSpan(1, context)
  }

  static newSpanOf(PendingTrace trace) {
    def fakePidProvider = [getPid: {-> return (Long)42}] as ISTSSpanContextPidProvider
    def fakeHostNameProvider = [getHostName: {-> return "fakehost"}] as ISTSSpanContextHostNameProvider
    def context = new STSSpanContext(
      trace.traceId,
      1L,
      0L,
      "fakeService",
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      Collections.emptyMap(),
      false,
      "fakeType",
      Collections.emptyMap(),
      trace,
      trace.tracer)
    context.setPidProvider(fakePidProvider)
    context.setHostNameProvider(fakeHostNameProvider)
    return new STSSpan(1, context)
  }

  static STSSpan newSpanOf(String serviceName, String envName) {
    def fakePidProvider = [getPid: {-> return (Long)42}] as ISTSSpanContextPidProvider
    def fakeHostNameProvider = [getHostName: {-> return "fakehost"}] as ISTSSpanContextHostNameProvider
    def writer = new ListWriter()
    def tracer = new STSTracer(writer)
    def context = new STSSpanContext(
      1L,
      1L,
      0L,
      serviceName,
      "fakeOperation",
      "fakeResource",
      PrioritySampling.UNSET,
      Collections.emptyMap(),
      false,
      "fakeType",
      Collections.emptyMap(),
      new PendingTrace(tracer, 1L),
      tracer)
    context.setTag("env", envName)
    context.setPidProvider(fakePidProvider)
    context.setHostNameProvider(fakeHostNameProvider)
    return new STSSpan(0l, context)
  }
}
