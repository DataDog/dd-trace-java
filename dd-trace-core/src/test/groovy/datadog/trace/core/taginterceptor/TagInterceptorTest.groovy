package datadog.trace.core.taginterceptor

import datadog.trace.agent.test.utils.ConfigUtils
import datadog.trace.api.DDSpanTypes
import datadog.trace.api.DDTags
import datadog.trace.api.sampling.PrioritySampling
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.common.sampling.AllSampler
import datadog.trace.common.writer.ListWriter
import datadog.trace.common.writer.LoggingWriter
import datadog.trace.core.CoreTracer
import datadog.trace.core.ExclusiveSpan
import datadog.trace.core.SpanFactory
import datadog.trace.util.test.DDSpecification

import static datadog.trace.api.ConfigDefaults.DEFAULT_SERVICE_NAME
import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE
import static datadog.trace.api.config.TracerConfig.SPLIT_BY_TAGS

class TagInterceptorTest extends DDSpecification {
  static {
    ConfigUtils.updateConfig {
      System.setProperty("dd.$SPLIT_BY_TAGS", "sn.tag1,sn.tag2")
    }
  }

  def cleanupSpec() {
    ConfigUtils.updateConfig {
      System.clearProperty("dd.$SPLIT_BY_TAGS")
    }
  }

  def writer = new ListWriter()
  def tracer = CoreTracer.builder().writer(writer).build()
  def span = SpanFactory.newSpanOf(tracer)

  def "adding span personalisation using Decorators"() {
    setup:
    def decorator = new AbstractTagInterceptor("foo") {
      boolean shouldSetTag(ExclusiveSpan span, String tag, Object value) {
        span.setTag("newFoo", value)
        return false
      }
    }
    tracer.addTagInterceptor(decorator)

    span.setTag("foo", "bar")

    expect:
    span.getTags().containsKey("newFoo")
    span.getTags().get("newFoo") == "bar"
  }

  def "set service name"() {
    setup:
    tracer = CoreTracer.builder()
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
    tracer = CoreTracer.builder()
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

    where:
    serviceName          | expected             | mapping
    DEFAULT_SERVICE_NAME | DEFAULT_SERVICE_NAME | ["other-service-name": "other-service"]
    DEFAULT_SERVICE_NAME | "new-service"        | [(DEFAULT_SERVICE_NAME): "new-service"]
    "other-service-name" | "other-service"      | ["other-service-name": "other-service"]
  }

  def "set service name from servlet.context with context '#context'"() {
    when:
    span.setTag(DDTags.SERVICE_NAME, serviceName)
    span.setTag("servlet.context", context)

    then:
    span.serviceName == expected

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

  def "mapping causes servlet.context to not change service name"() {
    setup:
    tracer = CoreTracer.builder()
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

    where:
    context         | serviceName
    "/some-context" | DEFAULT_SERVICE_NAME
    "/some-context" | "my-service"

    mapping = [(serviceName): "new-service"]
  }

  static createSplittingTracer(tag) {
    def tracer = CoreTracer.builder()
      .serviceName("my-service")
      .writer(new LoggingWriter())
      .sampler(new AllSampler())
      .build()

    // equivalent to split-by-tags: tag
    tracer.addTagInterceptor(new ServiceNameTagInterceptor(tag, true))

    return tracer
  }

  def "peer.service then split-by-tags via builder"() {
    setup:
    tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION)

    when:
    def span = tracer.buildSpan("some span")
      .withTag(Tags.PEER_SERVICE, "peer-service")
      .withTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
      .start()
    span.finish()

    then:
    span.serviceName == "some-queue"
  }

  def "peer.service then split-by-tags via setTag"() {
    setup:
    tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION)

    when:
    def span = tracer.buildSpan("some span").start()
    span.setTag(Tags.PEER_SERVICE, "peer-service")
    span.setTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
    span.finish()

    then:
    span.serviceName == "some-queue"
  }

  def "split-by-tags then peer-service via builder"() {
    setup:
    tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION)

    when:
    def span = tracer.buildSpan("some span")
      .withTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
      .withTag(Tags.PEER_SERVICE, "peer-service")
      .start()
    span.finish()

    then:
    span.serviceName == "peer-service"
  }

  def "split-by-tags then peer-service via setTag"() {
    setup:
    tracer = createSplittingTracer(Tags.MESSAGE_BUS_DESTINATION)

    when:
    def span = tracer.buildSpan("some span").start()
    span.setTag(Tags.MESSAGE_BUS_DESTINATION, "some-queue")
    span.setTag(Tags.PEER_SERVICE, "peer-service")
    span.finish()

    then:
    span.serviceName == "peer-service"
  }

  def "set resource name"() {
    when:
    span.setTag(DDTags.RESOURCE_NAME, name)
    span.finish()
    writer.waitForTraces(1)

    then:
    span.getResourceName() == name

    where:
    name = "my resource name"
  }

  def "set span type"() {
    when:
    span.setSpanType(type)
    span.finish()

    then:
    span.getSpanType() == type

    where:
    type = DDSpanTypes.HTTP_CLIENT
  }

  def "set span type with tag"() {
    when:
    span.setTag(DDTags.SPAN_TYPE, type)
    span.finish()
    writer.waitForTraces(1)

    then:
    span.getSpanType() == type

    where:
    type = DDSpanTypes.HTTP_CLIENT
  }

  def "span metrics starts empty but added with rate limiting value of #rate"() {
    expect:
    span.metrics == [:]

    when:
    span.setTag(ANALYTICS_SAMPLE_RATE, rate)
    span.finish()
    writer.waitForTraces(1)

    then:
    span.metrics.get(ANALYTICS_SAMPLE_RATE) == result

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
    span.setTag(tag, value)

    then:
    span.samplingPriority == expected

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

  def "DBStatementAsResource should not interact on Mongo queries"() {
    when:
    span.setResourceName("existing")
    span.setTag(Tags.COMPONENT, component)
    span.setTag(Tags.DB_STATEMENT, statement)
    span.finish()
    writer.waitForTraces(1)

    then:
    span.getResourceName() == resource

    where:
    component    | statement    | resource
    "java-mongo" | "some-query" | "existing"
    "other"      | "some-query" | "some-query"
  }

  def "set error flag when error tag reported"() {
    when:
    span.setTag(Tags.ERROR, error)
    span.finish()
    writer.waitForTraces(1)

    then:
    span.isError() == error

    where:
    error | _
    true  | _
    false | _
  }

  def "#attribute interceptors apply to builder too"() {
    setup:
    def span = tracer.buildSpan("interceptor.test").withTag(name, value).start()
    span.finish()
    writer.waitForTraces(1)

    expect:
    span.context()."$attribute" == value

    where:
    attribute      | name                 | value
    "serviceName"  | DDTags.SERVICE_NAME  | "my-service"
    "resourceName" | DDTags.RESOURCE_NAME | "my-resource"
    "spanType"     | DDTags.SPAN_TYPE     | "my-span-type"
  }

  def "decorators apply to builder too"() {
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
  }

  def "disable decorator via config"() {
    setup:
    ConfigUtils.updateConfig {
      System.setProperty("dd.trace.${decorator}.enabled", "$enabled")
    }

    tracer = CoreTracer.builder()
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
    ConfigUtils.updateConfig {
      System.clearProperty("dd.trace.${decorator}.enabled")
    }

    where:
    decorator                                               | enabled
    ServiceNameTagInterceptor.getSimpleName().toLowerCase() | true
    ServiceNameTagInterceptor.getSimpleName()               | true
    ServiceNameTagInterceptor.getSimpleName().toLowerCase() | false
    ServiceNameTagInterceptor.getSimpleName()               | false
  }

  def "disabling service decorator does not disable split by tags"() {
    setup:
    ConfigUtils.updateConfig {
      System.setProperty("dd.trace." + ServiceNameTagInterceptor.getSimpleName().toLowerCase() + ".enabled", "false")
    }

    tracer = CoreTracer.builder()
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
    ConfigUtils.updateConfig {
      System.clearProperty("dd.trace." + ServiceNameTagInterceptor.getSimpleName().toLowerCase() + ".enabled")
    }

    where:
    tag                 | name          | expected
    DDTags.SERVICE_NAME | "new-service" | "some-service"
    "service"           | "new-service" | "some-service"
    "sn.tag1"           | "new-service" | "new-service"


  }
}
