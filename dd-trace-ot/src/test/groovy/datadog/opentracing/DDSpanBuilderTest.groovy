package datadog.opentracing

import datadog.opentracing.propagation.ExtractedContext
import datadog.opentracing.propagation.TagContext
import datadog.trace.api.Config
import datadog.trace.api.DDTags
import datadog.trace.common.writer.ListWriter
import datadog.trace.util.test.DDSpecification
import io.opentracing.Scope
import io.opentracing.noop.NoopSpan
import static datadog.opentracing.DDSpanContext.ORIGIN_KEY
import static java.util.concurrent.TimeUnit.MILLISECONDS

class DDSpanBuilderTest extends DDSpecification {

  def writer = new ListWriter()
  def config = Config.get()
  def tracer = DDTracer.builder().writer(writer).build()

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

    DDTracer.DDSpanBuilder builder = tracer
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
      (DDTags.THREAD_NAME)     : Thread.currentThread().getName(),
      (DDTags.THREAD_ID)       : Thread.currentThread().getId(),
      (Config.RUNTIME_ID_TAG)  : config.getRuntimeId(),
      (Config.LANGUAGE_TAG_KEY): Config.LANGUAGE_TAG_VALUE,
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

    context.tags[DDTags.THREAD_NAME] == Thread.currentThread().getName()
    context.tags[DDTags.THREAD_ID] == Thread.currentThread().getId()
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
    final BigInteger spanId = 1G
    final BigInteger expectedParentId = spanId

    final DDSpanContext mockedContext = Mock()
    1 * mockedContext.getTraceId() >> spanId
    1 * mockedContext.getSpanId() >> spanId
    _ * mockedContext.getServiceName() >> "foo"
    1 * mockedContext.getBaggageItems() >> [:]
    1 * mockedContext.getTrace() >> new PendingTrace(tracer, 1G)

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
    final Scope parent = noopParent ?
      tracer.scopeManager().activate(NoopSpan.INSTANCE, false) :
      tracer.buildSpan("parent")
        .startActive(false)

    final BigInteger expectedParentId = noopParent ? 0G : new BigInteger(parent.span().context().toSpanId())

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


  def "should inherit the DD parent attributes addReference CHILD_OF"() {
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
        .addReference("child_of", parent.context())
        .start()

    println span.getBaggageItem(expectedBaggageItemKey)
    println expectedBaggageItemValue
    println span.context().getSpanType()
    println expectedParentType

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
        .addReference("child_of", parent.context())
        .start()

    then:
    span.getOperationName() == expectedName
    span.getBaggageItem(expectedBaggageItemKey) == expectedBaggageItemValue
    span.context().getServiceName() == expectedChildServiceName
    span.context().getResourceName() == expectedChildResourceName
    span.context().getSpanType() == expectedChildType
  }


  def "should inherit the DD parent attributes add reference FOLLOWS_FROM"() {
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
        .addReference("follows_from", parent.context())
        .start()

    println span.getBaggageItem(expectedBaggageItemKey)
    println expectedBaggageItemValue
    println span.context().getSpanType()
    println expectedParentType

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
        .addReference("follows_from", parent.context())
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
    span.context().@tags == extractedContext.tags + [(Config.RUNTIME_ID_TAG)  : config.getRuntimeId(),
                                                     (Config.LANGUAGE_TAG_KEY): Config.LANGUAGE_TAG_VALUE,
                                                     (DDTags.THREAD_NAME)     : thread.name, (DDTags.THREAD_ID): thread.id]

    where:
    extractedContext                                                                                                | _
    new ExtractedContext(1G, 2G, 0, null, [:], [:])                                                                 | _
    new ExtractedContext(3G, 4G, 1, "some-origin", ["asdf": "qwer"], [(ORIGIN_KEY): "some-origin", "zxcv": "1234"]) | _
  }

  def "TagContext should populate default span details"() {
    setup:
    def thread = Thread.currentThread()
    final DDSpan span = tracer.buildSpan("op name").asChildOf(tagContext).start()

    expect:
    span.traceId != 0G
    span.parentId == 0G
    span.samplingPriority == null
    span.context().origin == tagContext.origin
    span.context().baggageItems == [:]
    span.context().@tags == tagContext.tags + [(Config.RUNTIME_ID_TAG)  : config.getRuntimeId(),
                                               (Config.LANGUAGE_TAG_KEY): Config.LANGUAGE_TAG_VALUE,
                                               (DDTags.THREAD_NAME)     : thread.name, (DDTags.THREAD_ID): thread.id]

    where:
    tagContext                                                                   | _
    new TagContext(null, [:])                                                    | _
    new TagContext("some-origin", [(ORIGIN_KEY): "some-origin", "asdf": "qwer"]) | _
  }

  def "global span tags populated on each span"() {
    setup:
    System.setProperty("dd.trace.span.tags", tagString)
    def config = new Config()
    tracer = DDTracer.builder().config(config).writer(writer).build()
    def span = tracer.buildSpan("op name").withServiceName("foo").start()

    expect:
    span.tags == tags + [
      (DDTags.THREAD_NAME)     : Thread.currentThread().getName(),
      (DDTags.THREAD_ID)       : Thread.currentThread().getId(),
      (Config.RUNTIME_ID_TAG)  : config.getRuntimeId(),
      (Config.LANGUAGE_TAG_KEY): Config.LANGUAGE_TAG_VALUE,
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

  def "sanity test for logs if logHandler is null"() {
    setup:
    final String expectedName = "fakeName"

    final DDSpan span =
      tracer
        .buildSpan(expectedName)
        .withServiceName("foo")
        .start()
    final String expectedLogEvent = "fakeEvent"
    final timeStamp = System.currentTimeMillis()
    final Map<String, String> fieldsMap = new HashMap<>()

    span.log(expectedLogEvent)
    span.log(timeStamp, expectedLogEvent)
    span.log(fieldsMap)
    span.log(timeStamp, fieldsMap)
  }

  def "sanity test when passed log handler is null"() {
    setup:
    final String expectedName = "fakeName"
    final DDSpan span = tracer
      .buildSpan(expectedName)
      .withLogHandler(null)
      .start()
    final String expectedLogEvent = "fakeEvent"
    final timeStamp = System.currentTimeMillis()
    final Map<String, String> fieldsMap = new HashMap<>()

    span.log(expectedLogEvent)
    span.log(timeStamp, expectedLogEvent)
    span.log(fieldsMap)
    span.log(timeStamp, fieldsMap)
  }


  def "should delegate simple logs to logHandler"() {
    setup:
    final LogHandler logHandler = new TestLogHandler()
    final String expectedName = "fakeName"

    final DDSpan span =
      tracer
        .buildSpan(expectedName)
        .withLogHandler(logHandler)
        .withServiceName("foo")
        .start()
    final String expectedLogEvent = "fakeEvent"
    final timeStamp = System.currentTimeMillis()
    span.log(timeStamp, expectedLogEvent)

    expect:
    logHandler.assertLogCalledWithArgs(timeStamp, expectedLogEvent, span)
  }

  def "should delegate simple logs with timestamp to logHandler"() {
    setup:
    final LogHandler logHandler = new TestLogHandler()
    final String expectedName = "fakeName"

    final DDSpan span =
      tracer
        .buildSpan(expectedName)
        .withLogHandler(logHandler)
        .withServiceName("foo")
        .start()
    final String expectedLogEvent = "fakeEvent"
    span.log(expectedLogEvent)

    expect:
    logHandler.assertLogCalledWithArgs(expectedLogEvent, span)

  }

  def "should delegate logs with fields to logHandler"() {
    setup:
    final LogHandler logHandler = new TestLogHandler()
    final String expectedName = "fakeName"

    final DDSpan span =
      tracer
        .buildSpan(expectedName)
        .withLogHandler(logHandler)
        .withServiceName("foo")
        .start()
    final Map<String, String> fieldsMap = new HashMap<>()
    span.log(fieldsMap)

    expect:
    logHandler.assertLogCalledWithArgs(fieldsMap, span)

  }

  def "should delegate logs with fields and timestamp to logHandler"() {
    setup:
    final LogHandler logHandler = new TestLogHandler()
    final String expectedName = "fakeName"

    final DDSpan span =
      tracer
        .buildSpan(expectedName)
        .withLogHandler(logHandler)
        .withServiceName("foo")
        .start()
    final Map<String, String> fieldsMap = new HashMap<>()
    final timeStamp = System.currentTimeMillis()
    span.log(timeStamp, fieldsMap)

    expect:
    logHandler.assertLogCalledWithArgs(timeStamp, fieldsMap, span)

  }

  private static class TestLogHandler implements LogHandler {
    Object[] arguments = null

    @Override
    void log(Map<String, ?> fields, DDSpan span) {
      arguments = new Object[2]
      arguments[0] = fields
      arguments[1] = span
    }

    @Override
    void log(long timestampMicroseconds, Map<String, ?> fields, DDSpan span) {
      arguments = new Object[3]
      arguments[0] = timestampMicroseconds
      arguments[1] = fields
      arguments[2] = span
    }

    @Override
    void log(String event, DDSpan span) {
      arguments = new Object[2]
      arguments[0] = event
      arguments[1] = span
    }

    @Override
    void log(long timestampMicroseconds, String event, DDSpan span) {
      arguments = new Object[3]
      arguments[0] = timestampMicroseconds
      arguments[1] = event
      arguments[2] = span
    }

    boolean assertLogCalledWithArgs(Object... args) {
      if (arguments.size() != args.size()) {
        return false
      }
      for (int i = 0; i < args.size(); i++) {
        if (arguments[i] != args[i]) {
          return false
        }
      }
      return true
    }
  }
}
