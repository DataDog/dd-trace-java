package datadog.trace.core.taginterceptor

import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.env.CapturedEnvironment
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.sampling.AllSampler
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.LoggingWriter
import datadog.trace.core.CoreSpan
import datadog.trace.core.CoreTracer
import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME
import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS

class TagInterceptorTest extends DDSpecification {
  def setup() {
    injectSysConfig(SPLIT_BY_TAGS, "sn.tag1,sn.tag2")
  }

  def "set service name"() {
    setup:
    def tracer = CoreTracer.builder()
      .serviceName("wrong-service")
      .writer(new LoggingWriter())
      .sampler(new AllSampler())
      .serviceNameMappings(mapping)
      .build()

    when:
    def span = tracer.buildSpan("some span").withTag(tag, name).start()
    span.finish()

    then:
    span.getServiceName() == expected

    cleanup:
    tracer.close()

    where:
    tag                 | name            | expected
    DDTags.SERVICE_NAME | "some-service"  | "new-service"
    DDTags.SERVICE_NAME | "other-service" | "other-service"
    "service"           | "some-service"  | "new-service"
    "service"           | "other-service" | "other-service"
    Tags.PEER_SERVICE   | "some-service"  | "new-service"
    Tags.PEER_SERVICE   | "other-service" | "other-service"
    "sn.tag1"           | "some-service"  | "new-service"
    "sn.tag1"           | "other-service" | "other-service"
    "sn.tag2"           | "some-service"  | "new-service"
    "sn.tag2"           | "other-service" | "other-service"

    mapping = ["some-service": "new-service"]
  }

  def "default or configured service name can be remapped without setting tag"() {
    setup:
    def tracer = CoreTracer.builder()
      .serviceName(serviceName)
      .writer(new LoggingWriter())
      .sampler(new AllSampler())
      .serviceNameMappings(mapping)
      .build()

    when:
    def span = tracer.buildSpan("some span").start()
    span.finish()

    then:
    span.serviceName == expected

    cleanup:
    tracer.close()

    where:
    serviceName          | expected             | mapping
    DEFAULT_SERVICE_NAME | DEFAULT_SERVICE_NAME | ["other-service-name": "other-service"]
    DEFAULT_SERVICE_NAME | "new-service"        | [(DEFAULT_SERVICE_NAME): "new-service"]
    "other-service-name" | "other-service"      | ["other-service-name": "other-service"]
  }

  def "set service name from servlet.context with context '#context'"() {
    when:
    def tracer = CoreTracer.builder().writer(new ListWriter()).build()
    def span = tracer.buildSpan("test").start()
    span.setTag(DDTags.SERVICE_NAME, serviceName)
    span.setTag("servlet.context", context)

    then:
    span.serviceName == expected

    cleanup:
    tracer.close()

    where:
    context         | serviceName          | expected
    "/"             | DEFAULT_SERVICE_NAME | DEFAULT_SERVICE_NAME
    ""              | DEFAULT_SERVICE_NAME | DEFAULT_SERVICE_NAME
    "/some-context" | DEFAULT_SERVICE_NAME | "some-context"
    "other-context" | DEFAULT_SERVICE_NAME | "other-context"
    "/"             | "my-service"         | "my-service"
    ""              | "my-service"         | "my-service"
    "/some-context" | "my-service"         | "my-service"
    "other-context" | "my-service"         | "my-service"
  }

  def "setting service name as a property disables servlet.context with context '#context'"() {
    when:
    injectSysConfig("service", serviceName)
    def tracer = CoreTracer.builder().writer(new ListWriter()).build()
    def span = tracer.buildSpan("test").start()
    span.setTag("servlet.context", context)

    then:
    span.serviceName == serviceName

    cleanup:
    tracer.close()

    where:
    context         | serviceName
    "/"             | DEFAULT_SERVICE_NAME
    ""              | DEFAULT_SERVICE_NAME
    "/some-context" | DEFAULT_SERVICE_NAME
    "other-context" | DEFAULT_SERVICE_NAME
    "/"             | CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
    ""              | CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
    "/some-context" | CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
    "other-context" | CapturedEnvironment.get().getProperties().get(GeneralConfig.SERVICE_NAME)
    "/"             | "my-service"
    ""              | "my-service"
    "/some-context" | "my-service"
    "other-context" | "my-service"
  }

  def "mapping causes servlet.context to not change service name"() {
    setup:
    def tracer = CoreTracer.builder()
      .serviceName(serviceName)
      .writer(new LoggingWriter())
      .sampler(new AllSampler())
      .serviceNameMappings(mapping)
      .build()

    when:
    def span = tracer.buildSpan("some span").start()
    span.setTag("servlet.context", context)
    span.finish()

    then:
    span.serviceName == "new-service"

    cleanup:
    tracer.close()

    where:
    context         | serviceName
    "/some-context" | DEFAULT_SERVICE_NAME
    "/some-context" | "my-service"

    mapping = [(serviceName): "new-service"]
  }

  static createSplittingTracer(tag) {
    return CoreTracer.builder()
      .serviceName("my-service")
      .writer(new LoggingWriter())
      .sampler(new AllSampler())
    // equivalent to split-by-tags: tag
      .tagInterceptor(new TagInterceptor(true, "my-service",
        Collections.singleton(tag), new RuleFlags()))
      .build()
  }

  def "peer.service then split-by-tags via builder"() {
    setup:
    def tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION)

    when:
    def span = tracer.buildSpan("some span")
      .withTag(Tags.PEER_SERVICE, "peer-service")
      .withTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
      .start()
    span.finish()

    then:
    span.serviceName == "some-queue"

    cleanup:
    tracer.close()
  }

  def "peer.service then split-by-tags via setTag"() {
    setup:
    def tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION)

    when:
    def span = tracer.buildSpan("some span").start()
    span.setTag(Tags.PEER_SERVICE, "peer-service")
    span.setTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
    span.finish()

    then:
    span.serviceName == "some-queue"

    cleanup:
    tracer.close()
  }

  def "split-by-tags then peer-service via builder"() {
    setup:
    def tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION)

    when:
    def span = tracer.buildSpan("some span")
      .withTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
      .withTag(Tags.PEER_SERVICE, "peer-service")
      .start()
    span.finish()

    then:
    span.serviceName == "peer-service"

    cleanup:
    tracer.close()
  }

  def "split-by-tags then peer-service via setTag"() {
    setup:
    def tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION)

    when:
    def span = tracer.buildSpan("some span").start()
    span.setTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
    span.setTag(Tags.PEER_SERVICE, "peer-service")
    span.finish()

    then:
    span.serviceName == "peer-service"

    cleanup:
    tracer.close()
  }

  def "set resource name"() {
    when:
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()

    def span = tracer.buildSpan("test").start()
    span.setTag(DDTags.RESOURCE_NAME, name)
    span.finish()
    writer.waitForTraces(1)

    then:
    span.getResourceName() == name

    cleanup:
    tracer.close()

    where:
    name = "my resource name"
  }

  def "set span type"() {
    when:
    def tracer = CoreTracer.builder().writer(new ListWriter()).build()
    def span = tracer.buildSpan("test").start()
    span.setSpanType(type)
    span.finish()

    then:
    span.getSpanType() == type

    cleanup:
    tracer.close()

    where:
    type = DDSpanTypes.HTTP_CLIENT
  }

  def "set span type with tag"() {
    when:
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    def span = tracer.buildSpan("test").start()
    span.setTag(DDTags.SPAN_TYPE, type)
    span.finish()
    writer.waitForTraces(1)

    then:
    span.getSpanType() == type

    cleanup:
    tracer.close()

    where:
    type = DDSpanTypes.HTTP_CLIENT
  }

  def "span metrics starts empty but added with rate limiting value of #rate"() {
    when:
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    def span = tracer.buildSpan("test").start()

    then:
    span.getUnsafeMetrics() == [:]

    when:
    span.setTag(ANALYTICS_SAMPLE_RATE, rate)
    span.finish()
    writer.waitForTraces(1)

    then:
    span.getUnsafeMetrics().get(ANALYTICS_SAMPLE_RATE) == result

    cleanup:
    tracer.close()

    where:
    rate  | result
    00    | 0
    1     | 1
    0f    | 0
    1f    | 1
    0.1   | 0.1
    1.1   | 1.1
    -1    | -1
    10    | 10
    "00"  | 0
    "1"   | 1
    "1.0" | 1
    "0"   | 0
    "0.1" | 0.1
    "1.1" | 1.1
    "-1"  | -1
    "str" | null
  }

  def "set priority sampling via tag"() {
    when:
    def tracer = CoreTracer.builder().writer(new ListWriter()).build()
    def span = tracer.buildSpan("test").start()
    span.setTag(tag, value)

    then:
    span.samplingPriority == expected

    cleanup:
    tracer.close()
    
    where:
    tag                | value   | expected
    DDTags.MANUAL_KEEP | true    | PrioritySampling.USER_KEEP
    DDTags.MANUAL_KEEP | false   | null
    DDTags.MANUAL_KEEP | "true"  | PrioritySampling.USER_KEEP
    DDTags.MANUAL_KEEP | "false" | null
    DDTags.MANUAL_KEEP | "asdf"  | null

    DDTags.MANUAL_DROP | true    | PrioritySampling.USER_DROP
    DDTags.MANUAL_DROP | false   | null
    DDTags.MANUAL_DROP | "true"  | PrioritySampling.USER_DROP
    DDTags.MANUAL_DROP | "false" | null
    DDTags.MANUAL_DROP | "asdf"  | null
  }

  def "set error flag when error tag reported"() {
    when:
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()
    def span = tracer.buildSpan("test").start()
    span.setTag(Tags.ERROR, error)
    span.finish()
    writer.waitForTraces(1)

    then:
    span.isError() == error

    cleanup:
    tracer.close()

    where:
    error | _
    true  | _
    false | _
  }

  def "#attribute interceptors apply to builder too"() {
    setup:
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()

    when:
    def span = tracer.buildSpan("interceptor.test").withTag(name, value).start()
    span.finish()
    writer.waitForTraces(1)

    then:
    span.context()."$attribute" == value

    cleanup:
    tracer.close()

    where:
    attribute      | name                 | value
    "serviceName"  | DDTags.SERVICE_NAME  | "my-service"
    "resourceName" | DDTags.RESOURCE_NAME | "my-resource"
    "spanType"     | DDTags.SPAN_TYPE     | "my-span-type"
  }

  def "decorators apply to builder too"() {
    setup:
    def writer = new ListWriter()
    def tracer = CoreTracer.builder().writer(writer).build()

    when:
    def span = tracer.buildSpan("decorator.test").withTag("sn.tag1", "some val").start()
    span.finish()
    writer.waitForTraces(1)

    then:
    span.serviceName == "some val"

    when:
    span = tracer.buildSpan("decorator.test").withTag("servlet.context", "/my-servlet").start()

    then:
    span.serviceName == "my-servlet"

    when:
    span = tracer.buildSpan("decorator.test").withTag("error", "true").start()
    span.finish()
    writer.waitForTraces(2)

    then:
    span.error

    when:
    span = tracer.buildSpan("decorator.test").withTag(Tags.DB_STATEMENT, "some-statement").start()
    span.finish()
    writer.waitForTraces(3)

    then:
    span.resourceName.toString() == "some-statement"

    cleanup:
    tracer.close()
  }

  def "disable decorator via config"() {
    setup:
    injectSysConfig("dd.trace.${decorator}.enabled", "$enabled")

    def tracer = CoreTracer.builder()
      .serviceName("some-service")
      .writer(new LoggingWriter())
      .sampler(new AllSampler())
      .build()

    when:
    def span = tracer.buildSpan("some span").withTag(DDTags.SERVICE_NAME, "other-service").start()
    span.finish()

    then:
    span.getServiceName() == enabled ? "other-service" : "some-service"

    cleanup:
    tracer.close()

    where:
    decorator                   | enabled
    "servicenametaginterceptor" | true
    "ServiceNameTagInterceptor" | true
    "serviceNametaginterceptor" | false
    "ServiceNameTagInterceptor" | false
  }

  def "disabling service decorator does not disable split by tags"() {
    setup:
    injectSysConfig("dd.trace.ServiceNameTagInterceptor.enabled", "false")

    def tracer = CoreTracer.builder()
      .serviceName("some-service")
      .writer(new LoggingWriter())
      .sampler(new AllSampler())
      .build()

    when:
    def span = tracer.buildSpan("some span").withTag(tag, name).start()
    span.finish()

    then:
    span.getServiceName() == expected

    cleanup:
    tracer.close()

    where:
    tag                 | name          | expected
    DDTags.SERVICE_NAME | "new-service" | "some-service"
    "service"           | "new-service" | "some-service"
    "sn.tag1"           | "new-service" | "new-service"


  }

  def "change top level status when changing service name"() {
    setup:
    def tracer = CoreTracer.builder()
      .serviceName("some-service")
      .writer(new LoggingWriter())
      .sampler(new AllSampler())
      .build()

    AgentSpan parent = tracer.buildSpan("parent")
      .withServiceName("parent").start()

    when: "the service name doesn't match the parent"
    AgentSpan child = tracer.buildSpan("child")
      .withServiceName("child")
      .asChildOf(parent)
      .start()

    then:
    (child as CoreSpan).isTopLevel()

    when: "the service name is changed to match the parent"
    child.setTag(DDTags.SERVICE_NAME, "parent")

    then:
    !(child as CoreSpan).isTopLevel()

    when: "the service name is changed to no longer match the parent"
    child.setTag(DDTags.SERVICE_NAME, "foo")

    then:
    (child as CoreSpan).isTopLevel()

    cleanup:
    tracer.close()
  }
}
