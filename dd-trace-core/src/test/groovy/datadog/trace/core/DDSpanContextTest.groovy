package datadog.trace.core

import datadog.trace.api.DDTags
import datadog.trace.api.DDTraceId
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.TracePropagationStyle.DATADOG
import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MECHANISM_TAG
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_RULE_RATE_TAG
import static datadog.trace.core.DDSpanContext.SPAN_SAMPLING_MAX_PER_SECOND_TAG

class DDSpanContextTest extends DDCoreSpecification {

  def writer
  CoreTracer tracer
  def profilingContextIntegration

  def setup() {
    writer = new ListWriter()
    profilingContextIntegration = Mock(ProfilingContextIntegration)
    tracer = tracerBuilder().writer(writer)
      .profilingContextIntegration(profilingContextIntegration).build()
  }

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
    context.setErrorFlag(true, ErrorPriorities.DEFAULT)
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
      (name)               : value,
      (DDTags.THREAD_NAME) : thread.name,
      (DDTags.THREAD_ID)   : thread.id
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
    context.setSamplingPriority(SAMPLER_DROP, DEFAULT)
    then: "priority should be set"
    context.getSamplingPriority() == SAMPLER_DROP

    when: "sampling priority locked"
    context.lockSamplingPriority()
    then: "override ignored"
    !context.setSamplingPriority(USER_DROP, MANUAL)
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
    def extracted = new ExtractedContext(DDTraceId.from(123), 456, SAMPLER_KEEP, "789", tracer.getPropagationTagsFactory().empty(), DATADOG)
      .withRequestContextDataAppSec("dummy")

    def top = tracer.buildSpan("top").asChildOf((AgentSpanContext) extracted).start()
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

  def "set single span sampling tags"() {
    setup:
    def span = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .start()
    def context = span.context() as DDSpanContext

    expect:
    context.getSamplingPriority() == UNSET

    when:
    context.setSpanSamplingPriority(rate, limit)

    then:
    context.getTag(SPAN_SAMPLING_MECHANISM_TAG) == SPAN_SAMPLING_RATE
    context.getTag(SPAN_SAMPLING_RULE_RATE_TAG) == rate
    context.getTag(SPAN_SAMPLING_MAX_PER_SECOND_TAG) == (limit == Integer.MAX_VALUE ? null : limit)
    // single span sampling should not change the trace sampling priority
    context.getSamplingPriority() == UNSET
    // make sure the `_dd.p.dm` tag has not been set by single span sampling
    !context.getPropagationTags().createTagMap().containsKey("_dd.p.dm")

    where:
    rate | limit
    1.0  | 10
    0.5  | 100
    0.25 | Integer.MAX_VALUE
  }

  def "setting resource name to null is ignored"() {
    setup:
    def span = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .start()

    when:
    span.setResourceName(null)

    then:
    span.resourceName == "fakeResource"
  }

  def "setting operation name triggers constant encoding"() {
    when:
    def span = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .start()

    then: "encoded operation name matches operation name"
    1 * profilingContextIntegration.encodeOperationName("fakeOperation") >> 1
    1 * profilingContextIntegration.encodeResourceName("fakeResource") >> -1
    span.context.encodedOperationName == 1
    span.context.encodedResourceName == -1

    when:
    span.setOperationName("newOperationName")

    then:
    1 * profilingContextIntegration.encodeOperationName("newOperationName") >> 2
    span.context.encodedOperationName == 2

    when:
    span.setResourceName("newResourceName")

    then:
    1 * profilingContextIntegration.encodeResourceName("newResourceName") >> -2
    span.context.encodedResourceName == -2
  }

  private static String dataTag(String tag) {
    "_dd.${tag}.json"
  }

  def "Span IDs printed as unsigned long"() {
    setup:
    def parent = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .withSpanId(-987654321)
      .start()

    def span = tracer.buildSpan("fakeOperation")
      .withServiceName("fakeService")
      .withResourceName("fakeResource")
      .withSpanId(-123456789)
      .asChildOf(parent.context())
      .start()

    def context = span.context() as DDSpanContext

    expect:
    // even though span ID and parent ID are setup as negative numbers, they should be printed as their unsigned value
    // asserting there is no negative sign after ids is the best I can do.
    context.toString().contains("id=-") == false
  }

  def "toString includes top_level flag"() {
    setup:
    def parent = tracer.buildSpan("parentOperation")
      .withServiceName("parentService")
      .withResourceName("parentResource")
      .start()

    // Child span with different service name should be top-level
    def topLevelSpan = tracer.buildSpan("childOperation")
      .withServiceName("childService")
      .withResourceName("childResource")
      .asChildOf(parent.context())
      .start()

    // Child span with same service name should not be top-level
    def nonTopLevelSpan = tracer.buildSpan("childOperation2")
      .withServiceName("parentService")
      .withResourceName("childResource2")
      .asChildOf(parent.context())
      .start()

    def topLevelContext = topLevelSpan.context() as DDSpanContext
    def nonTopLevelContext = nonTopLevelSpan.context() as DDSpanContext

    expect:
    topLevelContext.isTopLevel() == true
    topLevelContext.toString().contains("*top_level*") == true
    nonTopLevelContext.isTopLevel() == false
    nonTopLevelContext.toString().contains("*top_level*") == false
  }

  static void assertTagmap(Map source, Map comparison, boolean removeThread = false) {
    def sourceWithoutCommonTags = new HashMap(source)
    sourceWithoutCommonTags.remove("runtime-id")
    sourceWithoutCommonTags.remove("language")
    sourceWithoutCommonTags.remove("_dd.agent_psr")
    sourceWithoutCommonTags.remove("_sample_rate")
    sourceWithoutCommonTags.remove("process_id")
    sourceWithoutCommonTags.remove("_dd.trace_span_attribute_schema")
    sourceWithoutCommonTags.remove(DDTags.PROFILING_ENABLED)
    sourceWithoutCommonTags.remove(DDTags.PROFILING_CONTEXT_ENGINE)
    sourceWithoutCommonTags.remove(DDTags.DSM_ENABLED)
    sourceWithoutCommonTags.remove(DDTags.DJM_ENABLED)
    if (removeThread) {
      sourceWithoutCommonTags.remove(DDTags.THREAD_ID)
      sourceWithoutCommonTags.remove(DDTags.THREAD_NAME)
    }
    assert sourceWithoutCommonTags == comparison
  }
}
