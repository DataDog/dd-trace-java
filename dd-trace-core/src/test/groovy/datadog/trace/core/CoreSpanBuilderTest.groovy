package datadog.trace.core

import datadog.trace.api.Config
import datadog.trace.api.DDId
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.propagation.TagContext
import datadog.trace.util.test.DDSpecification

import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG
import static datadog.trace.api.DDTags.THREAD_ID
import static datadog.trace.api.DDTags.THREAD_NAME
import static datadog.trace.core.DDSpanContext.ORIGIN_KEY
import static java.util.concurrent.TimeUnit.MILLISECONDS

class CoreSpanBuilderTest extends DDSpecification {

  def writer = new ListWriter()
  def config = Config.get()
  def tracer = CoreTracer.builder().writer(writer).build()

  def "build simple span"() {
    setup:
    final DDSpan span = tracer.buildSpan("op name").withServiceName("foo").start()

    expect:
    span.operationName == "op name"
  }

  def "build complex span"() {
    setup:
    def expectedName = "fakeName"
    def tags = [
      "1": true,
      "2": "fakeString",
      "3": 42.0,
    ]

    CoreTracer.CoreSpanBuilder builder = tracer
      .buildSpan(expectedName)
      .withServiceName("foo")
    tags.each {
      builder = builder.withTag(it.key, it.value)
    }

    when:
    DDSpan span = builder.start()

    then:
    span.getOperationName() == expectedName
    span.tags.subMap(tags.keySet()) == tags


    when:
    span = tracer.buildSpan(expectedName).withServiceName("foo").start()

    then:
    span.getTags() == [
      (THREAD_NAME)     : Thread.currentThread().getName(),
      (THREAD_ID)       : Thread.currentThread().getId(),
      (RUNTIME_ID_TAG)  : config.getRuntimeId(),
      (LANGUAGE_TAG_KEY): LANGUAGE_TAG_VALUE,
    ]

    when:
    // with all custom fields provided
    final String expectedResource = "fakeResource"
    final String expectedService = "fakeService"
    final String expectedType = "fakeType"

    span =
      tracer
        .buildSpan(expectedName)
        .withServiceName("foo")
        .withResourceName(expectedResource)
        .withServiceName(expectedService)
        .withErrorFlag()
        .withSpanType(expectedType)
        .start()

    final DDSpanContext context = span.context()

    then:
    context.getResourceName() == expectedResource
    context.getErrorFlag()
    context.getServiceName() == expectedService
    context.getSpanType() == expectedType

    context.getTag(THREAD_NAME) == Thread.currentThread().getName()
    context.getTag(THREAD_ID) == Thread.currentThread().getId()
  }

  def "setting #name should remove"() {
    setup:
    final DDSpan span = tracer.buildSpan("op name")
      .withTag(name, "tag value")
      .withTag(name, value)
      .start()

    expect:
    span.tags[name] == null

    when:
    span.setTag(name, "a tag")

    then:
    span.tags[name] == "a tag"

    when:
    span.setTag(name, (String) value)

    then:
    span.tags[name] == null

    where:
    name        | value
    "null.tag"  | null
    "empty.tag" | ""
  }

  def "should build span timestamp in nano"() {
    setup:
    // time in micro
    final long expectedTimestamp = 487517802L * 1000 * 1000L
    final String expectedName = "fakeName"

    DDSpan span =
      tracer
        .buildSpan(expectedName)
        .withServiceName("foo")
        .withStartTimestamp(expectedTimestamp)
        .start()

    expect:
    // get return nano time
    span.getStartTime() == expectedTimestamp * 1000L

    when:
    // auto-timestamp in nanoseconds
    def start = System.currentTimeMillis()
    span = tracer.buildSpan(expectedName).withServiceName("foo").start()
    def stop = System.currentTimeMillis()

    then:
    // Give a range of +/- 5 millis
    span.getStartTime() >= MILLISECONDS.toNanos(start - 1)
    span.getStartTime() <= MILLISECONDS.toNanos(stop + 1)
  }

  def "should link to parent span"() {
    setup:
    final DDId spanId = DDId.ONE
    final DDId expectedParentId = spanId

    final DDSpanContext mockedContext = Mock()
    1 * mockedContext.getTraceId() >> spanId
    1 * mockedContext.getSpanId() >> spanId
    _ * mockedContext.getServiceName() >> "foo"
    1 * mockedContext.getBaggageItems() >> [:]
    1 * mockedContext.getTrace() >> PendingTrace.create(tracer, DDId.ONE)

    final String expectedName = "fakeName"

    final DDSpan span =
      tracer
        .buildSpan(expectedName)
        .withServiceName("foo")
        .asChildOf(mockedContext)
        .start()

    final DDSpanContext actualContext = span.context()

    expect:
    actualContext.getParentId() == expectedParentId
    actualContext.getTraceId() == spanId
  }

  def "should link to parent span implicitly"() {
    setup:
    final AgentScope parent = tracer.activateSpan(noopParent ?
      AgentTracer.NoopAgentSpan.INSTANCE : tracer.buildSpan("parent").start())

    final DDId expectedParentId = noopParent ? DDId.ZERO : parent.span().context().getSpanId()

    final String expectedName = "fakeName"

    final DDSpan span = tracer
      .buildSpan(expectedName)
      .start()

    final DDSpanContext actualContext = span.context()

    expect:
    actualContext.getParentId() == expectedParentId

    cleanup:
    parent.close()

    where:
    noopParent << [false, true]
  }

  def "should inherit the DD parent attributes"() {
    setup:
    def expectedName = "fakeName"
    def expectedParentServiceName = "fakeServiceName"
    def expectedParentResourceName = "fakeResourceName"
    def expectedParentType = "fakeType"
    def expectedChildServiceName = "fakeServiceName-child"
    def expectedChildResourceName = "fakeResourceName-child"
    def expectedChildType = "fakeType-child"
    def expectedBaggageItemKey = "fakeKey"
    def expectedBaggageItemValue = "fakeValue"

    final DDSpan parent =
      tracer
        .buildSpan(expectedName)
        .withServiceName("foo")
        .withResourceName(expectedParentResourceName)
        .withSpanType(expectedParentType)
        .start()

    parent.setBaggageItem(expectedBaggageItemKey, expectedBaggageItemValue)

    // ServiceName and SpanType are always set by the parent  if they are not present in the child
    DDSpan span =
      tracer
        .buildSpan(expectedName)
        .withServiceName(expectedParentServiceName)
        .asChildOf(parent)
        .start()

    expect:
    span.getOperationName() == expectedName
    span.getBaggageItem(expectedBaggageItemKey) == expectedBaggageItemValue
    span.context().getServiceName() == expectedParentServiceName
    span.context().getResourceName() == expectedName
    span.context().getSpanType() == null

    when:
    // ServiceName and SpanType are always overwritten by the child  if they are present
    span =
      tracer
        .buildSpan(expectedName)
        .withServiceName(expectedChildServiceName)
        .withResourceName(expectedChildResourceName)
        .withSpanType(expectedChildType)
        .asChildOf(parent)
        .start()

    then:
    span.getOperationName() == expectedName
    span.getBaggageItem(expectedBaggageItemKey) == expectedBaggageItemValue
    span.context().getServiceName() == expectedChildServiceName
    span.context().getResourceName() == expectedChildResourceName
    span.context().getSpanType() == expectedChildType
  }

  def "should track all spans in trace"() {
    setup:
    List<DDSpan> spans = []
    final int nbSamples = 10

    // root (aka spans[0]) is the parent
    // others are just for fun

    def root = tracer.buildSpan("fake_O").withServiceName("foo").start()
    spans.add(root)

    final long tickEnd = System.currentTimeMillis()

    for (int i = 1; i <= 10; i++) {
      def span = tracer
        .buildSpan("fake_" + i)
        .withServiceName("foo")
        .asChildOf(spans.get(i - 1))
        .start()
      spans.add(span)
      span.finish()
    }
    root.finish(tickEnd)

    expect:
    root.context().getTrace().size() == nbSamples + 1
    root.context().getTrace().containsAll(spans)
    spans[(int) (Math.random() * nbSamples)].context.trace.containsAll(spans)
  }

  def "ExtractedContext should populate new span details"() {
    setup:
    def thread = Thread.currentThread()
    final DDSpan span = tracer.buildSpan("op name")
      .asChildOf(extractedContext).start()

    expect:
    span.traceId == extractedContext.traceId
    span.parentId == extractedContext.spanId
    span.samplingPriority == extractedContext.samplingPriority
    span.context().origin == extractedContext.origin
    span.context().baggageItems == extractedContext.baggage
    span.context().tags == extractedContext.tags + [(RUNTIME_ID_TAG)  : config.getRuntimeId(),
                                                            (LANGUAGE_TAG_KEY): LANGUAGE_TAG_VALUE,
                                                            (THREAD_NAME)     : thread.name, (THREAD_ID): thread.id]

    where:
    extractedContext                                                                                                                    | _
    new ExtractedContext(DDId.ONE, DDId.from(2), 0, null, [:], [:])                                                                     | _
    new ExtractedContext(DDId.from(3), DDId.from(4), 1, "some-origin", ["asdf": "qwer"], [(ORIGIN_KEY): "some-origin", "zxcv": "1234"]) | _
  }

  def "TagContext should populate default span details"() {
    setup:
    def thread = Thread.currentThread()
    final DDSpan span = tracer.buildSpan("op name").asChildOf(tagContext).start()

    expect:
    span.traceId != DDId.ZERO
    span.parentId == DDId.ZERO
    span.samplingPriority == null
    span.context().origin == tagContext.origin
    span.context().baggageItems == [:]
    span.context().tags == tagContext.tags + [(RUNTIME_ID_TAG)  : config.getRuntimeId(),
                                                      (LANGUAGE_TAG_KEY): LANGUAGE_TAG_VALUE,
                                                      (THREAD_NAME)     : thread.name, (THREAD_ID): thread.id]

    where:
    tagContext                                                                   | _
    new TagContext(null, [:])                                                    | _
    new TagContext("some-origin", [(ORIGIN_KEY): "some-origin", "asdf": "qwer"]) | _
  }

  def "global span tags populated on each span"() {
    setup:
    System.setProperty("dd.trace.span.tags", tagString)
    def config = new Config()
    tracer = CoreTracer.builder().config(config).writer(writer).build()
    def span = tracer.buildSpan("op name").withServiceName("foo").start()

    expect:
    span.tags == tags + [
      (THREAD_NAME)     : Thread.currentThread().getName(),
      (THREAD_ID)       : Thread.currentThread().getId(),
      (RUNTIME_ID_TAG)  : config.getRuntimeId(),
      (LANGUAGE_TAG_KEY): LANGUAGE_TAG_VALUE,
    ]

    cleanup:
    System.clearProperty("dd.trace.span.tags")

    where:
    tagString     | tags
    ""            | [:]
    "in:val:id"   | [:]
    "a:x"         | [a: "x"]
    "a:a,a:b,a:c" | [a: "c"]
    "a:1,b-c:d"   | [a: "1", "b-c": "d"]
  }
}
