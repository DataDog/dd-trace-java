package datadog.trace.core;

import static datadog.trace.api.TracePropagationStyle.DATADOG;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.api.DDTraceId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.junit.utils.tabletest.TableTestTypeConverters;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;
import org.tabletest.junit.TypeConverterSources;

@TypeConverterSources(TableTestTypeConverters.class)
public class DDSpanContextPropagationTagsTest extends DDCoreJavaSpecification {

  private ListWriter writer;
  private CoreTracer tracer;

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).build();
  }

  @TableTest({
    "scenario                        | priority     | header                              | newPriority  | newMechanism | newHeader                           | tagMap                                  ",
    "UNSET->USER_KEEP                | UNSET        | _dd.p.usr=123                       | USER_KEEP    | MANUAL       | _dd.p.dm=-4,_dd.p.usr=123           | [_dd.p.dm: -4, _dd.p.usr: 123]          ",
    "UNSET->SAMPLER_DROP             | UNSET        | _dd.p.usr=123                       | SAMPLER_DROP | DEFAULT      | _dd.p.usr=123                       | [_dd.p.usr: 123]                        ",
    "SAMPLER_KEEP->USER_KEEP with dm | SAMPLER_KEEP | _dd.p.dm=9bf3439f2f-1,_dd.p.usr=123 | USER_KEEP    | MANUAL       | _dd.p.dm=9bf3439f2f-1,_dd.p.usr=123 | [_dd.p.dm: 9bf3439f2f-1, _dd.p.usr: 123]",
    "SAMPLER_KEEP->USER_DROP with dm | SAMPLER_KEEP | _dd.p.dm=9bf3439f2f-1,_dd.p.usr=123 | USER_DROP    | MANUAL       | _dd.p.dm=9bf3439f2f-1,_dd.p.usr=123 | [_dd.p.dm: 9bf3439f2f-1, _dd.p.usr: 123]",
    "SAMPLER_KEEP->USER_KEEP no dm   | SAMPLER_KEEP | _dd.p.usr=123                       | USER_KEEP    | MANUAL       | _dd.p.usr=123                       | [_dd.p.usr: 123]                        "
  })
  void updateSpanPropagationTags(
      String scenario,
      int priority,
      String header,
      int newPriority,
      int newMechanism,
      String newHeader,
      Map<String, String> tagMap) {
    PropagationTags propagationTags =
        tracer
            .getPropagationTagsFactory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, header);
    AgentSpanContext extracted =
        new ExtractedContext(DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
            .withRequestContextDataAppSec("dummy");
    DDSpan span = (DDSpan) tracer.buildSpan("top").asChildOf(extracted).start();
    PropagationTags dd = span.context().getPropagationTags();

    span.setSamplingPriority(newPriority, newMechanism);

    assertEquals(newHeader, dd.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(tagMap, dd.createTagMap());
  }

  @TableTest({
    "scenario                        | priority     | header                              | newPriority  | newMechanism | rootHeader                          | rootTagMap                              ",
    "UNSET->USER_KEEP                | UNSET        | _dd.p.usr=123                       | USER_KEEP    | MANUAL       | _dd.p.dm=-4,_dd.p.usr=123           | [_dd.p.dm: -4, _dd.p.usr: 123]          ",
    "UNSET->SAMPLER_DROP             | UNSET        | _dd.p.usr=123                       | SAMPLER_DROP | DEFAULT      | _dd.p.usr=123                       | [_dd.p.usr: 123]                        ",
    "SAMPLER_KEEP->USER_KEEP with dm | SAMPLER_KEEP | _dd.p.dm=9bf3439f2f-1,_dd.p.usr=123 | USER_KEEP    | MANUAL       | _dd.p.dm=9bf3439f2f-1,_dd.p.usr=123 | [_dd.p.dm: 9bf3439f2f-1, _dd.p.usr: 123]",
    "SAMPLER_KEEP->USER_DROP with dm | SAMPLER_KEEP | _dd.p.dm=9bf3439f2f-1,_dd.p.usr=123 | USER_DROP    | MANUAL       | _dd.p.dm=9bf3439f2f-1,_dd.p.usr=123 | [_dd.p.dm: 9bf3439f2f-1, _dd.p.usr: 123]",
    "SAMPLER_KEEP->USER_KEEP no dm   | SAMPLER_KEEP | _dd.p.usr=123                       | USER_KEEP    | MANUAL       | _dd.p.usr=123                       | [_dd.p.usr: 123]                        "
  })
  void updateTracePropagationTags(
      String scenario,
      int priority,
      String header,
      int newPriority,
      int newMechanism,
      String rootHeader,
      Map<String, String> rootTagMap) {
    PropagationTags propagationTags =
        tracer
            .getPropagationTagsFactory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, header);
    AgentSpanContext extracted =
        new ExtractedContext(DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
            .withRequestContextDataAppSec("dummy");
    DDSpan rootSpan = (DDSpan) tracer.buildSpan("top").asChildOf(extracted).start();
    PropagationTags ddRoot = rootSpan.context().getPropagationTags();
    DDSpan span = (DDSpan) tracer.buildSpan("current").asChildOf(rootSpan.context()).start();

    span.setSamplingPriority(newPriority, newMechanism);

    assertEquals(rootHeader, ddRoot.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(rootTagMap, ddRoot.createTagMap());
  }

  @TableTest({
    "scenario             | priority     | header                              | newHeader                 | tagMap                        ",
    "UNSET                | UNSET        | _dd.p.usr=123                       | _dd.p.dm=-4,_dd.p.usr=123 | [_dd.p.dm: -4, _dd.p.usr: 123]",
    "SAMPLER_KEEP with dm | SAMPLER_KEEP | _dd.p.dm=9bf3439f2f-1,_dd.p.usr=123 | _dd.p.dm=-4,_dd.p.usr=123 | [_dd.p.dm: -4, _dd.p.usr: 123]",
    "SAMPLER_KEEP no dm   | SAMPLER_KEEP | _dd.p.usr=123                       | _dd.p.dm=-4,_dd.p.usr=123 | [_dd.p.dm: -4, _dd.p.usr: 123]"
  })
  void forceKeepSpanPropagationTags(
      String scenario, int priority, String header, String newHeader, Map<String, String> tagMap) {
    PropagationTags propagationTags =
        tracer
            .getPropagationTagsFactory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, header);
    AgentSpanContext extracted =
        new ExtractedContext(DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
            .withRequestContextDataAppSec("dummy");
    DDSpan span = (DDSpan) tracer.buildSpan("top").asChildOf(extracted).start();
    PropagationTags dd = span.context().getPropagationTags();

    span.context().forceKeep();

    assertEquals(newHeader, dd.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(tagMap, dd.createTagMap());
  }

  @TableTest({
    "scenario             | priority     | header                              | newHeader                 | tagMap                        ",
    "UNSET                | UNSET        | _dd.p.usr=123                       | _dd.p.dm=-4,_dd.p.usr=123 | [_dd.p.dm: -4, _dd.p.usr: 123]",
    "SAMPLER_KEEP with dm | SAMPLER_KEEP | _dd.p.dm=9bf3439f2f-1,_dd.p.usr=123 | _dd.p.dm=-4,_dd.p.usr=123 | [_dd.p.dm: -4, _dd.p.usr: 123]",
    "SAMPLER_KEEP no dm   | SAMPLER_KEEP | _dd.p.usr=123                       | _dd.p.dm=-4,_dd.p.usr=123 | [_dd.p.dm: -4, _dd.p.usr: 123]"
  })
  void forceKeepTracePropagationTags(
      String scenario,
      int priority,
      String header,
      String rootHeader,
      Map<String, String> rootTagMap) {
    PropagationTags propagationTags =
        tracer
            .getPropagationTagsFactory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, header);
    AgentSpanContext extracted =
        new ExtractedContext(DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
            .withRequestContextDataAppSec("dummy");
    DDSpan rootSpan = (DDSpan) tracer.buildSpan("top").asChildOf(extracted).start();
    PropagationTags ddRoot = rootSpan.context().getPropagationTags();
    DDSpan span = (DDSpan) tracer.buildSpan("current").asChildOf(rootSpan.context()).start();

    span.context().forceKeep();

    assertEquals(rootHeader, ddRoot.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(rootTagMap, ddRoot.createTagMap());
  }

  @TypeConverter
  public static int toInt(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Value cannot be null");
    }
    switch (value) {
      case "UNSET":
        return UNSET;
      case "SAMPLER_KEEP":
        return SAMPLER_KEEP;
      case "SAMPLER_DROP":
        return SAMPLER_DROP;
      case "USER_DROP":
        return USER_DROP;
      case "USER_KEEP":
        return USER_KEEP;
      case "MANUAL":
        return MANUAL;
      case "DEFAULT":
        return DEFAULT;
      default:
        throw new IllegalArgumentException("Value is invalid for " + value);
    }
  }
}
