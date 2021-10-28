package datadog.trace.core

import datadog.trace.api.DDId
import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP

class DDSpanContextTest extends DDCoreSpecification {

  def writer = new ListWriter()
  def tracer = tracerBuilder().writer(writer).build()

  def cleanup() {
    tracer.close()
  }

  def "null values for tags delete existing tags"() {
    setup:
    def span = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .withSpanType("fakeType")
      .start()
    def context = span.context()

    when:
    context.setTag("some.tag", "asdf")
    context.setTag(name, null)
    context.setErrorFlag(true)
    span.finish()

    writer.waitForTraces(1)

    then:
    assertTagmap(context.getTags(), tags)
    context.serviceName == "fakeService"
    context.resourceName.toString() == "fakeResource"
    context.spanType == "fakeType"

    where:
    name                 | tags
    DDTags.SERVICE_NAME  | ["some.tag": "asdf", (DDTags.THREAD_NAME): Thread.currentThread().name, (DDTags.THREAD_ID): Thread.currentThread().id]
    DDTags.RESOURCE_NAME | ["some.tag": "asdf", (DDTags.THREAD_NAME): Thread.currentThread().name, (DDTags.THREAD_ID): Thread.currentThread().id]
    DDTags.SPAN_TYPE     | ["some.tag": "asdf", (DDTags.THREAD_NAME): Thread.currentThread().name, (DDTags.THREAD_ID): Thread.currentThread().id]
    "some.tag"           | [(DDTags.THREAD_NAME): Thread.currentThread().name, (DDTags.THREAD_ID): Thread.currentThread().id]
  }

  def "special tags set certain values"() {
    setup:
    def span = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .withSpanType("fakeType")
      .start()
    def context = span.context()

    when:
    context.setTag(name, value)
    span.finish()
    writer.waitForTraces(1)

    then:
    def thread = Thread.currentThread()
    assertTagmap(context.getTags(), [(DDTags.THREAD_NAME): thread.name, (DDTags.THREAD_ID): thread.id])
    context."$method" == value

    where:
    name                 | value                | method         | details
    DDTags.SERVICE_NAME  | "different service"  | "serviceName"  | "different service/fakeOperation/fakeResource"
    DDTags.RESOURCE_NAME | "different resource" | "resourceName" | "fakeService/fakeOperation/different resource"
    DDTags.SPAN_TYPE     | "different type"     | "spanType"     | "fakeService/fakeOperation/fakeResource"
  }

  def "tags can be added to the context"() {
    setup:
    def span = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .withSpanType("fakeType")
      .start()
    def context = span.context()

    when:
    context.setTag(name, value)
    span.finish()
    writer.waitForTraces(1)
    def thread = Thread.currentThread()

    then:
    assertTagmap(context.getTags(), [
      (name)              : value,
      (DDTags.THREAD_NAME): thread.name,
      (DDTags.THREAD_ID)  : thread.id
    ])

    where:
    name             | value
    "tag.name"       | "some value"
    "tag with int"   | 1234
    "tag-with-bool"  | false
    "tag_with_float" | 0.321
  }

  def "metrics use the expected types"() {
    // floats should be converted to doubles.
    setup:
    def span = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .start()
    def context = span.context()

    when:
    context.setMetric("test", value)

    then:
    type.isInstance(context.getTag("test"))

    where:
    type    | value
    Integer | 0
    Integer | Integer.MAX_VALUE
    Integer | Integer.MIN_VALUE
    Short   | Short.MAX_VALUE
    Short   | Short.MIN_VALUE
    Float   | Float.MAX_VALUE
    Float   | Float.MIN_VALUE
    Double  | Double.MAX_VALUE
    Double  | Double.MIN_VALUE
    Float   | 1f
    Double  | 1d
    Float   | 0.5f
    Double  | 0.5d
    Integer | 0x55
  }

  def "force keep really keeps the trace"() {
    setup:
    def span = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .start()
    def context = span.context()
    when:
    context.setSamplingPriority(SAMPLER_DROP)
    then: "priority should be set"
    context.getSamplingPriority() == SAMPLER_DROP

    when: "sampling priority locked"
    context.lockSamplingPriority()
    then: "override ignored"
    !context.setSamplingPriority(USER_DROP)
    context.getSamplingPriority() == SAMPLER_DROP

    when:
    context.forceKeep()
    then: "lock is bypassed and priority set to USER_KEEP"
    context.getSamplingPriority() == USER_KEEP

    cleanup:
    span.finish()
  }


  def "set TraceSegment tags and data on correct span"() {
    setup:
    def extracted = new ExtractedContext(DDId.from(123), DDId.from(456), 1, "789", null, null, null, null, null, [:], [:]).withRequestContextData("dummy")
    def top = tracer.buildSpan("top").asChildOf((AgentSpan.Context) extracted).start()
    def topC = (DDSpanContext) top.context()
    def topTS = top.getRequestContext().getTraceSegment()
    def current = tracer.buildSpan("current").asChildOf(top).start()
    def currentTS = current.getRequestContext().getTraceSegment()
    def currentC = (DDSpanContext) current.context()

    when:
    currentTS.setDataTop("ctd", "[1]")
    currentTS.setTagTop("ctt", "t1")
    currentTS.setDataCurrent("ccd", "[2]")
    currentTS.setTagCurrent("cct", "t2")
    topTS.setDataTop("ttd", "[3]")
    topTS.setTagTop("ttt", "t3")
    topTS.setDataCurrent("tcd", "[4]")
    topTS.setTagCurrent("tct", "t4")

    then:
    assertTagmap(topC.getTags(), [(dataTag("ctd")): "[1]", "ctt": "t1",
      (dataTag("ttd")): "[3]", "ttt": "t3",
      (dataTag("tcd")): "[4]", "tct": "t4"], true)
    assertTagmap(currentC.getTags(), [(dataTag("ccd")): "[2]", "cct": "t2"], true)

    cleanup:
    current.finish()
    top.finish()
  }

  private static String dataTag(String tag) {
    "_dd.${tag}.json"
  }

  static void assertTagmap(Map source, Map comparison, boolean removeThread = false) {
    def sourceWithoutCommonTags = new HashMap(source)
    sourceWithoutCommonTags.remove("runtime-id")
    sourceWithoutCommonTags.remove("language")
    sourceWithoutCommonTags.remove("_dd.agent_psr")
    sourceWithoutCommonTags.remove("_sample_rate")
    if (removeThread) {
      sourceWithoutCommonTags.remove(DDTags.THREAD_ID)
      sourceWithoutCommonTags.remove(DDTags.THREAD_NAME)
    }
    assert sourceWithoutCommonTags == comparison
  }
}
