package datadog.trace.core

import datadog.trace.api.DDId
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*

class DDSpanContextDatadogTagsTest extends DDCoreSpecification {

  def writer = new ListWriter()
  def tracer

  def cleanup() {
    tracer.close()
  }

  def "update span DatadogTags"() {
    setup:
    tracer = tracerBuilder().writer(writer).build()
    def datadogTags = tracer.datadogTagsFactory.fromHeaderValue(header)
    def extracted = new ExtractedContext(DDId.from(123), DDId.from(456), priority, "789", 0, [:], [:], null, datadogTags)
    .withRequestContextDataAppSec("dummy")
    def span = (DDSpan) tracer.buildSpan("top")
      .asChildOf((AgentSpan.Context) extracted)
      .start()
    def dd = span.context().getDatadogTags()

    when:
    span.setSamplingPriority(newPriority, newMechanism)
    span.getSamplingPriority() == newPriority

    then:
    dd.headerValue() == newHeader
    dd.createTagMap() == tagMap

    where:
    priority     | header                                | newPriority  | newMechanism | newHeader                             | tagMap
    UNSET        | "_dd.p.usr=123"                       | USER_KEEP    | MANUAL       | "_dd.p.usr=123,_dd.p.dm=-4"           | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
    UNSET        | "_dd.p.usr=123"                       | SAMPLER_DROP | DEFAULT      | "_dd.p.usr=123"                       | ["_dd.p.usr": "123"]
    // decision has already been made, propagate as-is
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | USER_KEEP    | MANUAL       | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | ["_dd.p.dm": "9bf3439f2f-1", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | USER_DROP    | MANUAL       | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | ["_dd.p.dm": "9bf3439f2f-1", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.usr=123"                       | USER_KEEP    | MANUAL       | "_dd.p.usr=123"                       | ["_dd.p.usr": "123"]
  }

  def "update trace DatadogTags"() {
    setup:
    tracer = tracerBuilder().writer(writer).build()
    def datadogTags = tracer.datadogTagsFactory.fromHeaderValue(header)
    def extracted = new ExtractedContext(DDId.from(123), DDId.from(456), priority, "789", 0, [:], [:], null, datadogTags)
    .withRequestContextDataAppSec("dummy")
    def rootSpan = (DDSpan) tracer.buildSpan("top")
      .asChildOf((AgentSpan.Context) extracted)
      .start()
    def ddRoot = rootSpan.context().getDatadogTags()
    def span = (DDSpan) tracer.buildSpan("current").asChildOf(rootSpan).start()
    def dd = span.context().getDatadogTags()

    when:
    span.setSamplingPriority(newPriority, newMechanism)
    span.getSamplingPriority() == newPriority

    then:
    dd.headerValue() == null
    dd.createTagMap() == spanTagMap
    ddRoot.headerValue() == rootHeader
    ddRoot.createTagMap() == rootTagMap

    where:
    priority     | header                                | newPriority  | newMechanism | rootHeader                            | spanTagMap | rootTagMap
    UNSET        | "_dd.p.usr=123"                       | USER_KEEP    | MANUAL       | "_dd.p.usr=123,_dd.p.dm=-4"           | [:]        | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
    UNSET        | "_dd.p.usr=123"                       | SAMPLER_DROP | DEFAULT      | "_dd.p.usr=123"                       | [:]        | ["_dd.p.usr": "123"]
    // decision has already been made, propagate as-is
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | USER_KEEP    | MANUAL       | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | [:]        | ["_dd.p.dm": "9bf3439f2f-1", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | USER_DROP    | MANUAL       | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | [:]        | ["_dd.p.dm": "9bf3439f2f-1", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.usr=123"                       | USER_KEEP    | MANUAL       | "_dd.p.usr=123"                       | [:]        | ["_dd.p.usr": "123"]
  }

  def "forceKeep span DatadogTags"() {
    setup:
    tracer = tracerBuilder().writer(writer).build()
    def datadogTags = tracer.datadogTagsFactory.fromHeaderValue(header)
    def extracted = new ExtractedContext(DDId.from(123), DDId.from(456), priority, "789", 0, [:], [:], null, datadogTags)
    .withRequestContextDataAppSec("dummy")
    def span = (DDSpan) tracer.buildSpan("top")
      .asChildOf((AgentSpan.Context) extracted)
      .start()
    def dd = span.context().getDatadogTags()

    when:
    span.context().forceKeep()
    span.getSamplingPriority() == USER_KEEP

    then:
    dd.headerValue() == newHeader
    dd.createTagMap() == tagMap

    where:
    priority     | header                                | newHeader                             | tagMap
    UNSET        | "_dd.p.usr=123"                       | "_dd.p.usr=123,_dd.p.dm=-4"           | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
    // decision has already been made, propagate as-is
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | ["_dd.p.dm": "9bf3439f2f-1", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.usr=123"                       | "_dd.p.usr=123"                       | ["_dd.p.usr": "123"]
  }

  def "forceKeep trace DatadogTags"() {
    setup:
    tracer = tracerBuilder().writer(writer).build()
    def datadogTags = tracer.datadogTagsFactory.fromHeaderValue(header)
    def extracted = new ExtractedContext(DDId.from(123), DDId.from(456), priority, "789", 0, [:], [:], null, datadogTags)
    .withRequestContextDataAppSec("dummy")
    def rootSpan = (DDSpan) tracer.buildSpan("top")
      .asChildOf((AgentSpan.Context) extracted)
      .start()
    def ddRoot = rootSpan.context().getDatadogTags()
    def span = (DDSpan) tracer.buildSpan("current").asChildOf(rootSpan).start()
    def dd = span.context().getDatadogTags()

    when:
    span.context().forceKeep()
    span.getSamplingPriority() == USER_KEEP

    then:
    dd.headerValue() == null
    dd.createTagMap() == spanTagMap
    ddRoot.headerValue() == rootHeader
    ddRoot.createTagMap() == rootTagMap

    where:
    priority     | header                                | rootHeader                            | spanTagMap | rootTagMap
    UNSET        | "_dd.p.usr=123"                       | "_dd.p.usr=123,_dd.p.dm=-4"           | [:]        | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
    // decision has already been made, propagate as-is
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | [:]        | ["_dd.p.dm": "9bf3439f2f-1", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.usr=123"                       | "_dd.p.usr=123"                       | [:]        | ["_dd.p.usr": "123"]
  }
}
