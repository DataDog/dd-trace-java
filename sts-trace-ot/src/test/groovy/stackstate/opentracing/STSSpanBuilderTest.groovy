package stackstate.opentracing

import stackstate.trace.api.STSTags
import stackstate.trace.common.writer.ListWriter
import spock.lang.Specification

import static java.util.concurrent.TimeUnit.MILLISECONDS
import static org.mockito.Mockito.mock
import static org.mockito.Mockito.when

class STSSpanBuilderTest extends Specification {
  def writer = new ListWriter()
  def tracer = new STSTracer(writer)

  def fakePidProvider = [getPid: {-> return (Long)42}] as ISTSSpanContextPidProvider
  def fakeHostNameProvider = [getHostName: {-> return "fakehost"}] as ISTSSpanContextHostNameProvider


  def "build simple span"() {
    setup:
    final STSSpan span = tracer.buildSpan("op name").withServiceName("foo").start()

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

    STSTracer.STSSpanBuilder builder = tracer
      .buildSpan(expectedName)
      .withServiceName("foo")
      .withHostNameProvider(fakeHostNameProvider)
      .withPidProvider(fakePidProvider)
    tags.each {
      builder = builder.withTag(it.key, it.value)
    }

    when:
    STSSpan span = builder.start()

    then:
    span.getOperationName() == expectedName
    span.tags.subMap(tags.keySet()) == tags


    when:
    span = tracer
      .buildSpan(expectedName)
      .withServiceName("foo")
      .withHostNameProvider(fakeHostNameProvider)
      .withPidProvider(fakePidProvider)
      .start()

    then:
    span.getTags() == [
      (STSTags.THREAD_NAME): Thread.currentThread().getName(),
      (STSTags.THREAD_ID)  : Thread.currentThread().getId(),
      (STSTags.SPAN_HOSTNAME): fakeHostNameProvider.getHostName(),
      (STSTags.SPAN_PID): fakePidProvider.getPid()
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
        .withHostNameProvider(fakeHostNameProvider)
        .withPidProvider(fakePidProvider)
        .start()

    final STSSpanContext context = span.context()
    context.setHostNameProvider(fakeHostNameProvider)
    context.setPidProvider(fakePidProvider)

    then:
    context.getResourceName() == expectedResource
    context.getErrorFlag()
    context.getServiceName() == expectedService
    context.getSpanType() == expectedType

    context.tags[STSTags.THREAD_NAME] == Thread.currentThread().getName()
    context.tags[STSTags.THREAD_ID] == Thread.currentThread().getId()
  }

  def "should build span timestamp in nano"() {
    setup:
    // time in micro
    final long expectedTimestamp = 487517802L * 1000 * 1000L
    final String expectedName = "fakeName"

    STSSpan span =
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
    final long spanId = 1L
    final long expectedParentId = spanId

    final STSSpanContext mockedContext = mock(STSSpanContext)

    when(mockedContext.getSpanId()).thenReturn(spanId)
    when(mockedContext.getServiceName()).thenReturn("foo")
    when(mockedContext.getTrace()).thenReturn(new PendingTrace(tracer, 1L))

    final String expectedName = "fakeName"

    final STSSpan span =
      tracer
        .buildSpan(expectedName)
        .withServiceName("foo")
        .asChildOf(mockedContext)
        .start()

    final STSSpanContext actualContext = span.context()

    expect:
    actualContext.getParentId() == expectedParentId
  }

  def "should inherit the STS parent attributes"() {
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

    final STSSpan parent =
      tracer
        .buildSpan(expectedName)
        .withServiceName("foo")
        .withResourceName(expectedParentResourceName)
        .withSpanType(expectedParentType)
        .start()

    parent.setBaggageItem(expectedBaggageItemKey, expectedBaggageItemValue)

    // ServiceName and SpanType are always set by the parent  if they are not present in the child
    STSSpan span =
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
    span.context().getSpanType() == expectedParentType

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
    List<STSSpan> spans = []
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

  def "global span tags populated on each span"() {
    setup:
    System.setProperty("sts.trace.span.tags", tagString)
    tracer = new STSTracer(writer)
    def fakePidProvider = [getPid: {-> return (Long)42}] as ISTSSpanContextPidProvider
    def fakeHostNameProvider = [getHostName: {-> return "fakehost"}] as ISTSSpanContextHostNameProvider
    def span = tracer.buildSpan("op name")
      .withServiceName("foo")
      .withPidProvider(fakePidProvider)
      .withHostNameProvider(fakeHostNameProvider)
      .start()
    tags.putAll([
      (STSTags.THREAD_NAME): Thread.currentThread().getName(),
      (STSTags.THREAD_ID)  : Thread.currentThread().getId(),
      (STSTags.SPAN_HOSTNAME): "fakehost",
      (STSTags.SPAN_PID)  : (Long) 42
    ])

    expect:
    span.tags == tags

    cleanup:
    System.clearProperty("sts.trace.span.tags")

    where:
    tagString     | tags
    ""            | [:]
    "in:val:id"   | [:]
    "a:x"         | [a: "x"]
    "a:a,a:b,a:c" | [a: "c"]
    "a:1,b-c:d"   | [a: "1", "b-c": "d"]
  }
}
