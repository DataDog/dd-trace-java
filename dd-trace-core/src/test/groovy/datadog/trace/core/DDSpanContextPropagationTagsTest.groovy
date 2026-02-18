package datadog.trace.core

import datadog.trace.api.DDTraceId
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext
import datadog.trace.common.writer.ListWriter
import datadog.trace.core.propagation.ExtractedContext
import datadog.trace.core.propagation.PropagationTags
import datadog.trace.core.test.DDCoreSpecification

import static datadog.trace.api.TracePropagationStyle.DATADOG
import static datadog.trace.api.sampling.PrioritySampling.*
import static datadog.trace.api.sampling.SamplingMechanism.*

class DDSpanContextPropagationTagsTest extends DDCoreSpecification {

  def writer = new ListWriter()
  def tracer

  def cleanup() {
    tracer.close()
  }

  def "update span PropagationTags #priority #header"() {
    setup:
    tracer = tracerBuilder().writer(writer).build()
    def propagationTags = tracer.propagationTagsFactory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, header)
    def extracted = new ExtractedContext(DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
      .withRequestContextDataAppSec("dummy")
    def span = (DDSpan) tracer.buildSpan("top")
      .asChildOf((AgentSpanContext) extracted)
      .start()
    def dd = span.context().getPropagationTags()

    when:
    span.setSamplingPriority(newPriority, newMechanism)
    span.getSamplingPriority() == newPriority

    then:
    dd.headerValue(PropagationTags.HeaderType.DATADOG) == newHeader
    dd.createTagMap() == tagMap

    where:
    priority     | header                                | newPriority  | newMechanism | newHeader                             | tagMap
    UNSET        | "_dd.p.usr=123"                       | USER_KEEP    | MANUAL       | "_dd.p.dm=-4,_dd.p.usr=123"           | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
    UNSET        | "_dd.p.usr=123"                       | SAMPLER_DROP | DEFAULT      | "_dd.p.usr=123"                       | ["_dd.p.usr": "123"]
    // decision has already been made, propagate as-is
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | USER_KEEP    | MANUAL       | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | ["_dd.p.dm": "9bf3439f2f-1", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | USER_DROP    | MANUAL       | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | ["_dd.p.dm": "9bf3439f2f-1", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.usr=123"                       | USER_KEEP    | MANUAL       | "_dd.p.usr=123"                       | ["_dd.p.usr": "123"]
  }

  def "update trace PropagationTags #priority #header"() {
    setup:
    tracer = tracerBuilder().writer(writer).build()
    def propagationTags = tracer.propagationTagsFactory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, header)
    def extracted = new ExtractedContext(DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
      .withRequestContextDataAppSec("dummy")
    def rootSpan = (DDSpan) tracer.buildSpan("top")
      .asChildOf((AgentSpanContext) extracted)
      .start()
    def ddRoot = rootSpan.context().getPropagationTags()
    def span = (DDSpan) tracer.buildSpan("current").asChildOf(rootSpan).start()

    when:
    span.setSamplingPriority(newPriority, newMechanism)
    span.getSamplingPriority() == newPriority

    then:
    ddRoot.headerValue(PropagationTags.HeaderType.DATADOG) == rootHeader
    ddRoot.createTagMap() == rootTagMap

    where:
    priority     | header                                | newPriority  | newMechanism | rootHeader                            | rootTagMap
    UNSET        | "_dd.p.usr=123"                       | USER_KEEP    | MANUAL       | "_dd.p.dm=-4,_dd.p.usr=123"           | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
    UNSET        | "_dd.p.usr=123"                       | SAMPLER_DROP | DEFAULT      | "_dd.p.usr=123"                       | ["_dd.p.usr": "123"]
    // decision has already been made, propagate as-is
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | USER_KEEP    | MANUAL       | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | ["_dd.p.dm": "9bf3439f2f-1", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | USER_DROP    | MANUAL       | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | ["_dd.p.dm": "9bf3439f2f-1", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.usr=123"                       | USER_KEEP    | MANUAL       | "_dd.p.usr=123"                       | ["_dd.p.usr": "123"]
  }

  def "forceKeep span PropagationTags #priority #header"() {
    setup:
    tracer = tracerBuilder().writer(writer).build()
    def propagationTags = tracer.propagationTagsFactory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, header)
    def extracted = new ExtractedContext(DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
      .withRequestContextDataAppSec("dummy")
    def span = (DDSpan) tracer.buildSpan("top")
      .asChildOf((AgentSpanContext) extracted)
      .start()
    def dd = span.context().getPropagationTags()

    when:
    span.context().forceKeep()
    span.getSamplingPriority() == USER_KEEP

    then:
    dd.headerValue(PropagationTags.HeaderType.DATADOG) == newHeader
    dd.createTagMap() == tagMap

    where:
    priority     | header                                | newHeader                   | tagMap
    UNSET        | "_dd.p.usr=123"                       | "_dd.p.dm=-4,_dd.p.usr=123" | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | "_dd.p.dm=-4,_dd.p.usr=123" | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.usr=123"                       | "_dd.p.dm=-4,_dd.p.usr=123" | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
  }

  def "forceKeep trace PropagationTags #priority #header"() {
    setup:
    tracer = tracerBuilder().writer(writer).build()
    def propagationTags = tracer.propagationTagsFactory.fromHeaderValue(PropagationTags.HeaderType.DATADOG, header)
    def extracted = new ExtractedContext(DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
      .withRequestContextDataAppSec("dummy")
    def rootSpan = (DDSpan) tracer.buildSpan("top")
      .asChildOf((AgentSpanContext) extracted)
      .start()
    def ddRoot = rootSpan.context().getPropagationTags()
    def span = (DDSpan) tracer.buildSpan("current").asChildOf(rootSpan).start()

    when:
    span.context().forceKeep()
    span.getSamplingPriority() == USER_KEEP

    then:
    ddRoot.headerValue(PropagationTags.HeaderType.DATADOG) == rootHeader
    ddRoot.createTagMap() == rootTagMap

    where:
    priority     | header                                | rootHeader                  | rootTagMap
    UNSET        | "_dd.p.usr=123"                       | "_dd.p.dm=-4,_dd.p.usr=123" | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123" | "_dd.p.dm=-4,_dd.p.usr=123" | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
    SAMPLER_KEEP | "_dd.p.usr=123"                       | "_dd.p.dm=-4,_dd.p.usr=123" | ["_dd.p.dm": "-4", "_dd.p.usr": "123"]
  }
}
