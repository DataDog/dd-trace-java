package stackstate.trace

import stackstate.opentracing.SpanFactory
import stackstate.trace.api.STSTags
import spock.lang.Specification
import spock.lang.Timeout

@Timeout(1)
class STSSpanContextTest extends Specification {

  def "null values for tags delete existing tags"() {
    setup:
    def context = SpanFactory.newSpanOf(0).context
    context.setTag("some.tag", "asdf")
    context.setTag(name, null)
    context.setErrorFlag(true)

    expect:
    context.getTags() == tags
    context.serviceName == "fakeService"
    context.resourceName == "fakeResource"
    context.spanType == "fakeType"
    context.toString() == "Span [ t_id=1, s_id=1, p_id=0] trace=fakeService/fakeOperation/fakeResource *errored* tags={${extra}span.type=${context.getSpanType()}, thread.id=${Thread.currentThread().id}, thread.name=${Thread.currentThread().name}}"

    where:
    name                  | extra             | tags
    STSTags.SERVICE_NAME  | "some.tag=asdf, " | ["some.tag": "asdf", (STSTags.SPAN_TYPE):"fakeType", (STSTags.THREAD_NAME): Thread.currentThread().name, (STSTags.THREAD_ID): Thread.currentThread().id]
    STSTags.RESOURCE_NAME | "some.tag=asdf, " | ["some.tag": "asdf", (STSTags.SPAN_TYPE):"fakeType", (STSTags.THREAD_NAME): Thread.currentThread().name, (STSTags.THREAD_ID): Thread.currentThread().id]
    STSTags.SPAN_TYPE     | "some.tag=asdf, " | ["some.tag": "asdf", (STSTags.SPAN_TYPE):"fakeType", (STSTags.THREAD_NAME): Thread.currentThread().name, (STSTags.THREAD_ID): Thread.currentThread().id]
    "some.tag"            | ""                | [(STSTags.SPAN_TYPE):"fakeType", (STSTags.THREAD_NAME): Thread.currentThread().name, (STSTags.THREAD_ID): Thread.currentThread().id]
  }

  def "special tags set certain values"() {
    setup:
    def context = SpanFactory.newSpanOf(0).context
    context.setTag(name, value)
    def thread = Thread.currentThread()

    def expectedTags = [(STSTags.THREAD_NAME): thread.name, (STSTags.THREAD_ID): thread.id, (STSTags.SPAN_TYPE): context.getSpanType()]
    def expectedTrace = "Span [ t_id=1, s_id=1, p_id=0] trace=$details tags={span.type=${context.getSpanType()}, thread.id=$thread.id, thread.name=$thread.name}"

    expect:
    context.getTags() == expectedTags
    context."$method" == value
    context.toString() == expectedTrace

    where:
    name                  | value                | method         | details
    STSTags.SERVICE_NAME  | "different service"  | "serviceName"  | "different service/fakeOperation/fakeResource"
    STSTags.RESOURCE_NAME | "different resource" | "resourceName" | "fakeService/fakeOperation/different resource"
    STSTags.SPAN_TYPE     | "different type"     | "spanType"     | "fakeService/fakeOperation/fakeResource"
  }

  def "tags can be added to the context"() {
    setup:
    def context = SpanFactory.newSpanOf(0).context
    context.setTag(name, value)
    def thread = Thread.currentThread()

    expect:
    context.getTags() == [
      (name)               : value,
      (STSTags.SPAN_TYPE)  : context.getSpanType(),
      (STSTags.THREAD_NAME): thread.name,
      (STSTags.THREAD_ID)  : thread.id
    ]
    context.toString() == "Span [ t_id=1, s_id=1, p_id=0] trace=fakeService/fakeOperation/fakeResource tags={span.type=${context.getSpanType()}, $name=$value, thread.id=$thread.id, thread.name=$thread.name}"

    where:
    name             | value
    "tag.name"       | "some value"
    "tag with int"   | 1234
    "tag-with-bool"  | false
    "tag_with_float" | 0.321
  }
}
