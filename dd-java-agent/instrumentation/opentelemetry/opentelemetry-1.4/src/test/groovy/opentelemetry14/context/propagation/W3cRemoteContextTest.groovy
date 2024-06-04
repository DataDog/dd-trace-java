package opentelemetry14.context.propagation

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.api.DDSpanId
import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.api.TracePropagationStyle
import datadog.trace.api.interceptor.MutableSpan
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities
import datadog.trace.common.writer.ListWriter
import datadog.trace.context.TraceScope
import datadog.trace.core.DDSpan
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.propagation.PropagationTags
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context

//import datadog.trace.instrumentation.opentracing.DefaultLogHandler
//import datadog.trace.instrumentation.opentracing32.OTTracer
//import datadog.trace.instrumentation.opentracing32.TypeConverter
//import io.opentracing.References
//import io.opentracing.Scope
//import io.opentracing.Span
//import io.opentracing.log.Fields
//import io.opentracing.noop.NoopSpan
//import io.opentracing.propagation.Format
//import io.opentracing.propagation.TextMap
//import io.opentracing.util.GlobalTracer
import spock.lang.Shared
import spock.lang.Subject

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.api.DDTags.ORIGIN_KEY
import static datadog.trace.api.DDTags.THREAD_ID
import static datadog.trace.api.DDTags.THREAD_NAME
import static datadog.trace.api.TracePropagationStyle.DATADOG
import static datadog.trace.api.TracePropagationStyle.NONE
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP
import static datadog.trace.api.sampling.PrioritySampling.UNSET
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP
import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL

import datadog.trace.agent.test.AgentTestRunner
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.context.Context
import spock.lang.Subject




  class W3cRemoteContextTest extends AgentTestRunner {
    @Subject
    def tracer = GlobalOpenTelemetry.get().tracerProvider.get("tracecontext-propagator-tracestate")

    @Override
    void configurePreAgent() {
      super.configurePreAgent()

      injectSysConfig("dd.integration.opentelemetry.experimental.enabled", "true")
      injectSysConfig("dd.trace.propagation.style", "tracecontext")
    }

    def "AAAAAAAAAA"() {
      setup:
      // Get agent propagator injected by instrumentation
      def propagator = GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()
      def headers = [
        'traceparent': '00-22222222222222222222222222222222-4444444444444444-00'
      ]
      def members = new String[0]
      if (tracestate) {
        headers['tracestate'] = tracestate
        members = Arrays.stream(tracestate.split(','))
          .filter { !it.startsWith("dd=")}
          .toArray(String[]::new)
      }

      when:
      def context = propagator.extract(Context.root(), headers, TextMap.INSTANCE)

      then:
      context != Context.root()

      when:
      def localSpan = tracer.spanBuilder("some-name")
        .setParent(context)
        .startSpan()
      def scope = localSpan.makeCurrent()
      Map<String, String> injectedHeaders = [:]
      propagator.inject(Context.current(), injectedHeaders, TextMap.INSTANCE)
      scope.close()
      localSpan.end()

      then:
      // Check tracestate was injected
      def injectedTracestate = injectedHeaders['tracestate']
      injectedTracestate != null
      // Check tracestate contains extracted members plus the Datadog one in first position
      def injectedMembers = injectedTracestate.split(',')
      injectedMembers.length == Math.min(1 + members.length, 32)
      injectedMembers[0] == expect
      for (int i = 0; i< Math.min(members.length, 31); i++) {
        assert injectedMembers[i+1] == members[i]
      }

      where:
      tracestate            |expect
      "foo=1,bar=2"         |"dd=s:0;t.tid:1111111111111111"
      "dd=s:0,foo=1,bar=2"  |"dd=s:0;p:0000000000000000;t.tid:1111111111111111"
      "foo=1,dd=s:0,bar=2"  |"dd=s:0;p:0000000000000000;t.tid:1111111111111111"
      "dd=s:3"              |"dd=s:0;p:0000000000000000;t.tid:1111111111111111"
    }
  }

//  def writer = new ListWriter()
//  def tracer = tracerBuilder().writer(writer).build()
//  def tracer = GlobalTracer.get()
//  def tracer = GlobalOpenTelemetry.get().tracerProvider.get("tracecontext-propagator-tracestate")
//
//  def "XXXXXXXXXX"() {
//    setup:
//    def thread = Thread.currentThread()
//    final DDSpan span = (DDSpan) tracer.spanBuilder("testing").setParent(extractedContext).startSpan()
//      //.asChildOf(extractedContext).start()
//
//    expect:
//    span.traceId == extractedContext.traceId
//    span.parentId == extractedContext.spanId
//    span.samplingPriority == extractedContext.samplingPriority
//    span.context().origin == extractedContext.origin
//    span.context().baggageItems == extractedContext.baggage
//    // check the extracted context has been copied into the span tags
//    for (Map.Entry<String, Object> tag : extractedContext.tags) {
//      span.context().tags.get(tag.getKey()) == tag.getValue()
//    }
//    span.getTag(THREAD_ID) == thread.id
//    span.getTag(THREAD_NAME) == thread.name
//    span.context().propagationTags.headerValue(PropagationTags.HeaderType.DATADOG) == extractedContext.propagationTags.headerValue(PropagationTags.HeaderType.DATADOG)
//
//    where:
//    extractedContext                                                                                                                                                                                                                         | _
//    new ExtractedContext(DDTraceId.ONE, 2, PrioritySampling.SAMPLER_DROP, null, null, 0, [:], [:], null, PropagationTags.factory().fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.dm=934086a686-4,_dd.p.anytag=value"), null, DATADOG) | _
//    new ExtractedContext(DDTraceId.from(3), 4, PrioritySampling.SAMPLER_KEEP, "some-origin", "4444444444444444", 0, ["asdf": "qwer"], [(ORIGIN_KEY): "some-origin", "zxcv": "1234"], null, PropagationTags.factory().empty(), null, DATADOG)                     | _
//  }

//  def "test remote context2"() {
//    setup:
//    //tracer = tracerBuilder().writer(writer).build()
//    def propagationTags = tracer.propagationTagsFactory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, header)
//    def extracted = new ExtractedContext(DDTraceId.from(123), 456, priority, "789", "4444444444444444", propagationTags, TracePropagationStyle.TRACECONTEXT)
//      .withRequestContextDataAppSec("dummy")
//    def rootSpan = (DDSpan) tracer.buildSpan("top")
//      .asChildOf((AgentSpan.Context) extracted)
//      .start()
//    def ddRoot = rootSpan.context().getPropagationTags()
//    def span = (DDSpan) tracer.buildSpan("current").asChildOf(rootSpan).start()
//
//    when:
//    span.context().forceKeep()
//    span.getSamplingPriority() == USER_KEEP
//
//    then:
//    ddRoot.headerValue(PropagationTags.HeaderType.DATADOG) == rootHeader
//    ddRoot.createTagMap() == rootTagMap
//
//    where:
//    priority     | header                                | rootHeader                            | rootTagMap
//    UNSET        | "_dd.p.usr=1234"                       | "_dd.p.dm=-4,_dd.p.usr=123"           | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
//    // decision has already been made, propagate as-is
//    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=1234" | ["_dd.p.dm": "9bf3439f2f-1", "_dd.p.usr": "123"]
//    SAMPLER_KEEP | "_dd.p.usr=1234"                       | "_dd.p.usr=123"                       | ["_dd.p.usr": "123"]
//  }




//  @Subject
//  def tracer = GlobalTracer.get()
//
//  @Shared
//  TypeConverter typeConverter = new TypeConverter(new DefaultLogHandler())

//  @Subject
//  def tracer = GlobalOpenTelemetry.get().tracerProvider.get("tracecontext-propagator-tracestate")
//
//  @Override
//  void configurePreAgent() {
//    super.configurePreAgent()
//
//    injectSysConfig("dd.integration.opentelemetry.experimental.enabled", "true")
//    injectSysConfig("dd.trace.propagation.style", "tracecontext")
//  }
//
//  def "test remote context"() {
//    setup:
//    // Get agent propagator injected by instrumentation
//    def propagator = GlobalOpenTelemetry.get().getPropagators().getTextMapPropagator()
//    def headers = [
//      'traceparent': '00-22222222222222222222222222222222-3333333333333333-00'
//    ]
//    def members = new String[0]
//    if (tracestate) {
//      headers['tracestate'] = tracestate
//      members = Arrays.stream(tracestate.split(','))
//        .filter { !it.startsWith("dd=")}
//        .toArray(String[]::new)
//    }
//
//    when:
//    //def context = propagator.extract(Context.root(), headers, TextMap.INSTANCE)
//    def extracted = new ExtractedContext(DDTraceId.ONE, 2, SAMPLER_KEEP, null, "4444444444444444", PropagationTags.factory().empty(), TracePropagationStyle.TRACECONTEXT)
//
//
//    then:
//    extracted != Context.root()
//
//    when:
////    def localSpan = tracer.spanBuilder("some-name").setp
////     // .setParent(context)
////      .startSpan()
//
//    //def remoteSpan =
//
//    def remoteSpan = tracer.spanBuilder("top").setParent().startSpan()
////    sb...spanBuilder("top")...buildSpan("top")
////      .asChildOf((AgentSpan.Context) extracted)
////      .start()
//
//    def scope = remoteSpan.makeCurrent()
//    Map<String, String> injectedHeaders = [:]
//    propagator.inject(Context.current(), injectedHeaders, TextMap.INSTANCE)
//    scope.close()
//    remoteSpan.end()
//
//    then:
//    // Check tracestate was injected
//    def injectedTracestate = injectedHeaders['tracestate']
//    injectedTracestate != null
//    // Check tracestate contains extracted members plus the Datadog one in first position
//    def injectedMembers = injectedTracestate.split(',')
//    injectedMembers.length == Math.min(1 + members.length, 32)
//    injectedMembers[0] == expect
//    for (int i = 0; i< Math.min(members.length, 31); i++) {
//      assert injectedMembers[i+1] == members[i]
//    }
//
//    where:
//    tracestate            |expect
//    "foo=1,bar=2"         |"dd=s:0;t.tid:2222222222222222"
//    "dd=s:0,foo=1,bar=2"  |"dd=s:0;p:0000000000000000;t.tid:2222222222222222"
//    "foo=1,dd=s:0,bar=2"  |"dd=s:0;p:0000000000000000;t.tid:2222222222222222"
//    "dd=s:3"              |"dd=s:0;p:0000000000000000;t.tid:2222222222222222"
//  }

//  def "test #method"() {
//    setup:
//    def builder = tracer.buildSpan("some name")
//    if (tagBuilder) {
//      builder.withTag(DDTags.RESOURCE_NAME, "some resource")
//        .withTag("string", "a")
//        .withTag("number", 1)
//        .withTag("boolean", true)
//    }
//    if (addReference) {
//      def ctx = new ExtractedContext(DDTraceId.ONE, 2, SAMPLER_DROP, null, null, PropagationTags.factory().empty(), NONE)
//      builder.addReference(addReference, tracer.tracer.converter.toSpanContext(ctx))
//    }
//    def result = builder.start()
//    if (tagSpan) {
//      result.setTag(DDTags.RESOURCE_NAME, "other resource")
//        .setTag("string", "b")
//        .setTag("number", 2)
//        .setTag("boolean", false)
//    }
//    if (exception) {
//      result.log([(Fields.ERROR_OBJECT): exception])
//    }
//
//    expect:
//    result instanceof MutableSpan
//    (result as MutableSpan).localRootSpan.delegate == result.delegate
//    (result as MutableSpan).isError() == (exception != null)
//    tracer.activeSpan() == null
//    result.context().baggageItems().isEmpty()
//
//    when:
//    result.setBaggageItem("test", "baggage")
//
//    then:
//    result.getBaggageItem("test") == "baggage"
//    result.context().baggageItems() == ["test": "baggage"].entrySet()
//
//    when:
//    result.finish()
//
//    then:
//    assertTraces(1) {
//      trace(1) {
//        span {
//          if ([References.CHILD_OF, References.FOLLOWS_FROM].contains(addReference)) {
//            parentSpanId(2)
//          } else {
//            parent()
//          }
//          operationName "some name"
//          if (tagSpan) {
//            resourceName "other resource"
//          } else if (tagBuilder) {
//            resourceName "some resource"
//          } else {
//            resourceName "some name"
//          }
//          errored exception != null
//          tags {
//            if (tagSpan) {
//              "string" "b"
//              "number" 2
//              "boolean" false
//            } else if (tagBuilder) {
//              "string" "a"
//              "number" 1
//              "boolean" true
//            }
//            if (exception) {
//              errorTags(exception.class)
//            }
//            defaultTags(addReference != null)
//          }
//        }
//      }
//    }
//
//    where:
//    method        | addReference            | tagBuilder | tagSpan | exception
//    "start"       | null                    | true       | false   | null
//    "startManual" | References.CHILD_OF     | true       | true    | new Exception()
//    "startManual" | "bogus"                 | false      | false   | new Exception()
//    "start"       | References.FOLLOWS_FROM | false      | true    | null
//  }

//  def "test ignoreParent"() {
//    setup:
//    def otherSpan = runUnderTrace("parent") {
//      tracer.buildSpan("other").ignoreActiveSpan().start()
//    }
//
//    expect:
//    otherSpan.operationName == "other"
//    (otherSpan.delegate as DDSpan).parentId == DDSpanId.ZERO
//  }
//
//  def "test startActive"() {
//    setup:
//    def scope = tracer.buildSpan("some name").startActive(finishSpan)
//
//    expect:
//    scope instanceof TraceScope
//    tracer.activeSpan().delegate == scope.span().delegate
//
//    when:
//    scope.close()
//
//    then:
//    (scope.span().delegate as DDSpan).isFinished() == finishSpan
//
//    cleanup:
//    if (finishSpan) {
//      TEST_WRITER.waitForTraces(1)
//    }
//
//    where:
//    finishSpan << [true, false]
//  }
//
//  def "test scopemanager"() {
//    setup:
//    def span = tracer.buildSpan("some name").start()
//    def scope = tracer.scopeManager().activate(span, finishSpan)
//    (scope as TraceScope).setAsyncPropagation(false)
//
//    expect:
//    span instanceof MutableSpan
//    scope instanceof TraceScope
//    !(scope as TraceScope).isAsyncPropagating()
//    (scope as TraceScope).capture() == null
//    (tracer.scopeManager().active().span().delegate == span.delegate)
//
//    when:
//    (scope as TraceScope).setAsyncPropagation(true)
//    def continuation = (scope as TraceScope).capture()
//    continuation.cancel()
//
//    then:
//    (scope as TraceScope).isAsyncPropagating()
//    continuation instanceof TraceScope.Continuation
//
//    when: "attempting to close the span this way doesn't work because we lost the 'finishSpan' reference"
//    tracer.scopeManager().active().close()
//
//    then:
//    !(span.delegate as DDSpan).isFinished()
//
//    when:
//    scope.close()
//
//    then:
//    (span.delegate as DDSpan).isFinished() == finishSpan
//
//    cleanup:
//    if (finishSpan) {
//      TEST_WRITER.waitForTraces(1)
//    }
//
//    where:
//    finishSpan | _
//    true       | _
//    false      | _
//  }
//
//  def "test scopemanager with non OTSpan"() {
//    setup:
//    def span = NoopSpan.INSTANCE
//    def scope = tracer.scopeManager().activate(span, true)
//
//    expect:
//    !(span instanceof MutableSpan)
//    scope instanceof TraceScope
//
//    and: "non OTSpans aren't supported and get converted to NoopAgentSpan"
//    tracer.scopeManager().active().span() != span
//
//    when:
//    scope.close()
//    scope.span().finish()
//
//    then:
//    assertTraces(0) {}
//  }
//
//  def "test continuation"() {
//    setup:
//    def span = tracer.buildSpan("some name").start()
//    TraceScope scope = tracer.scopeManager().activate(span, false)
//    scope.setAsyncPropagation(true)
//
//    expect:
//    tracer.activeSpan().delegate == span.delegate
//
//    when:
//    def continuation = scope.capture()
//
//    then:
//    continuation instanceof TraceScope.Continuation
//
//    when:
//    scope.close()
//
//    then:
//    tracer.activeSpan() == null
//
//    when:
//    scope = continuation.activate()
//
//    then:
//    tracer.activeSpan().delegate == span.delegate
//
//    cleanup:
//    scope.close()
//  }
//
//  def "closing scope when not on top"() {
//    when:
//    Span firstSpan = tracer.buildSpan("someOperation").start()
//    Scope firstScope = tracer.scopeManager().activate(firstSpan)
//
//    Span secondSpan = tracer.buildSpan("someOperation").start()
//    Scope secondScope = tracer.scopeManager().activate(secondSpan)
//
//    firstSpan.finish()
//    firstScope.close()
//
//    then:
//    tracer.scopeManager().active().delegate == secondScope.delegate
//    _ * TEST_PROFILING_CONTEXT_INTEGRATION._
//    0 * _
//
//    when:
//    secondSpan.finish()
//    secondScope.close()
//
//    then:
//    assert tracer.scopeManager().active() == null
//  }
//
//  def "test inject extract"() {
//    setup:
//    def context = tracer.buildSpan("some name").start().context()
//    def textMap = [:]
//    def adapter = new TextMapAdapter(textMap)
//
//    when:
//    context.delegate.setSamplingPriority(contextPriority, samplingMechanism)
//    tracer.inject(context, Format.Builtin.TEXT_MAP, adapter)
//
//    then:
//    def expectedTraceparent = "00-${context.delegate.traceId.toHexStringPadded(32)}" +
//      "-${DDSpanId.toHexStringPadded(context.delegate.spanId)}" +
//      "-" + (propagatedPriority > 0 ? "01" : "00")
//    def expectedTracestate = "dd=s:${propagatedPriority};p:${DDSpanId.toHexStringPadded(context.delegate.spanId)}"
//    def expectedDatadogTags = null
//    if (propagatedPriority > 0) {
//      def effectiveSamplingMechanism = contextPriority == UNSET ? AGENT_RATE : samplingMechanism
//      expectedDatadogTags = "_dd.p.dm=-" + effectiveSamplingMechanism
//      expectedTracestate+= ";t.dm:-" + effectiveSamplingMechanism
//    }
//    def expectedTextMap = [
//      "x-datadog-trace-id"         : "$context.delegate.traceId",
//      "x-datadog-parent-id"        : "$context.delegate.spanId",
//      "x-datadog-sampling-priority": propagatedPriority.toString(),
//      "traceparent"                : expectedTraceparent,
//      "tracestate"                 : expectedTracestate
//    ]
//    if (expectedDatadogTags != null) {
//      expectedTextMap.put("x-datadog-tags", expectedDatadogTags)
//    }
//    textMap == expectedTextMap
//
//    when:
//    def extract = tracer.extract(Format.Builtin.TEXT_MAP, adapter)
//
//    then:
//    extract.delegate.traceId == context.delegate.traceId
//    extract.delegate.spanId == context.delegate.spanId
//    extract.delegate.samplingPriority == propagatedPriority
//
//    where:
//    contextPriority | samplingMechanism | propagatedPriority
//    SAMPLER_DROP    | DEFAULT           | SAMPLER_DROP
//    SAMPLER_KEEP    | DEFAULT           | SAMPLER_KEEP
//    UNSET           | DEFAULT           | SAMPLER_KEEP
//    USER_KEEP       | MANUAL            | USER_KEEP
//    USER_DROP       | MANUAL            | USER_DROP
//  }
//
//  def "tolerate null span activation"() {
//    when:
//    try {
//      tracer.scopeManager().activate(null)?.close()
//    } catch (Exception ignored) {}
//
//    try {
//      tracer.activateSpan(null)?.close()
//    } catch (Exception ignored) {}
//
//    // make sure scope stack has been left in a valid state
//    Span testSpan = tracer.buildSpan("someOperation").start()
//    Scope testScope = tracer.scopeManager().activate(testSpan)
//    testSpan.finish()
//    testScope.close()
//
//    then:
//    assert tracer.scopeManager().active() == null
//  }
//
//  def "test resource name assignment through MutableSpan casting"() {
//    given:
//    OTTracer.OTSpanBuilder builder = tracer.buildSpan("parent") as OTTracer.OTSpanBuilder
//    builder.delegate.withResourceName("test-resource")
//    Span testSpan = builder.start()
//    Scope testScope = tracer.activateSpan(testSpan)
//
//    when:
//    Span active = GlobalTracer.get().activeSpan()
//    Span child = GlobalTracer.get().buildSpan("child").asChildOf(active).start()
//    Scope scope = GlobalTracer.get().activateSpan(child)
//
//    MutableSpan localRootSpan = ((MutableSpan) child).getLocalRootSpan()
//    localRootSpan.setResourceName("correct-resource")
//
//    then:
//    typeConverter.toAgentSpan(testSpan).getResourceName() == "correct-resource"
//
//    when:
//    typeConverter.toAgentSpan(testSpan).setResourceName("should-be-ignored", ResourceNamePriorities.HTTP_FRAMEWORK_ROUTE)
//
//    then:
//    typeConverter.toAgentSpan(testSpan).getResourceName() == "correct-resource"
//
//    cleanup:
//    scope.close()
//    child.finish()
//    testScope.close()
//    testSpan.finish()
//  }
//
//  static class TextMapAdapter implements TextMap {
//    private final Map<String, String> map
//
//    TextMapAdapter(Map<String, String> map) {
//      this.map = map
//    }
//
//    @Override
//    Iterator<Map.Entry<String, String>> iterator() {
//      return map.entrySet().iterator()
//    }
//
//    @Override
//    void put(String key, String value) {
//      map.put(key, value)
//    }
//  }


