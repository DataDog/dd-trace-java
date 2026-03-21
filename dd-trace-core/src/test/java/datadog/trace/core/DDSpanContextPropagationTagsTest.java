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
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.propagation.ExtractedContext;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class DDSpanContextPropagationTagsTest extends DDCoreSpecification {

  ListWriter writer = new ListWriter();
  CoreTracer tracer;

  @AfterEach
  void cleanup() {
    tracer.close();
  }

  @ParameterizedTest
  @MethodSource("updateSpanPropagationTagsArguments")
  void updateSpanPropagationTags(
      int priority,
      String header,
      int newPriority,
      int newMechanism,
      String newHeader,
      Map<String, String> tagMap)
      throws Exception {
    tracer = tracerBuilder().writer(writer).build();
    PropagationTags propagationTags =
        tracer
            .getPropagationTagsFactory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, header);
    ExtractedContext extracted =
        (ExtractedContext)
            new ExtractedContext(
                    DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
                .withRequestContextDataAppSec("dummy");
    DDSpan span = (DDSpan) tracer.buildSpan("top").asChildOf(extracted).start();
    PropagationTags dd = span.context().getPropagationTags();

    span.setSamplingPriority(newPriority, newMechanism);

    assertEquals(newHeader, dd.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(tagMap, dd.createTagMap());
  }

  static Stream<Arguments> updateSpanPropagationTagsArguments() {
    return Stream.of(
        Arguments.of(
            UNSET,
            "_dd.p.usr=123",
            USER_KEEP,
            MANUAL,
            "_dd.p.dm=-4,_dd.p.usr=123",
            mapOf("_dd.p.dm", "-4", "_dd.p.usr", "123")),
        Arguments.of(
            UNSET,
            "_dd.p.usr=123",
            SAMPLER_DROP,
            DEFAULT,
            "_dd.p.usr=123",
            mapOf("_dd.p.usr", "123")),
        // decision has already been made, propagate as-is
        Arguments.of(
            SAMPLER_KEEP,
            "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123",
            USER_KEEP,
            MANUAL,
            "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123",
            mapOf("_dd.p.dm", "9bf3439f2f-1", "_dd.p.usr", "123")),
        Arguments.of(
            SAMPLER_KEEP,
            "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123",
            USER_DROP,
            MANUAL,
            "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123",
            mapOf("_dd.p.dm", "9bf3439f2f-1", "_dd.p.usr", "123")),
        Arguments.of(
            SAMPLER_KEEP,
            "_dd.p.usr=123",
            USER_KEEP,
            MANUAL,
            "_dd.p.usr=123",
            mapOf("_dd.p.usr", "123")));
  }

  @ParameterizedTest
  @MethodSource("updateTracePropagationTagsArguments")
  void updateTracePropagationTags(
      int priority,
      String header,
      int newPriority,
      int newMechanism,
      String rootHeader,
      Map<String, String> rootTagMap)
      throws Exception {
    tracer = tracerBuilder().writer(writer).build();
    PropagationTags propagationTags =
        tracer
            .getPropagationTagsFactory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, header);
    ExtractedContext extracted =
        (ExtractedContext)
            new ExtractedContext(
                    DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
                .withRequestContextDataAppSec("dummy");
    DDSpan rootSpan = (DDSpan) tracer.buildSpan("top").asChildOf(extracted).start();
    PropagationTags ddRoot = rootSpan.context().getPropagationTags();
    DDSpan span = (DDSpan) tracer.buildSpan("current").asChildOf(rootSpan.context()).start();

    span.setSamplingPriority(newPriority, newMechanism);

    assertEquals(rootHeader, ddRoot.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(rootTagMap, ddRoot.createTagMap());
  }

  static Stream<Arguments> updateTracePropagationTagsArguments() {
    return Stream.of(
        Arguments.of(
            UNSET,
            "_dd.p.usr=123",
            USER_KEEP,
            MANUAL,
            "_dd.p.dm=-4,_dd.p.usr=123",
            mapOf("_dd.p.dm", "-4", "_dd.p.usr", "123")),
        Arguments.of(
            UNSET,
            "_dd.p.usr=123",
            SAMPLER_DROP,
            DEFAULT,
            "_dd.p.usr=123",
            mapOf("_dd.p.usr", "123")),
        // decision has already been made, propagate as-is
        Arguments.of(
            SAMPLER_KEEP,
            "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123",
            USER_KEEP,
            MANUAL,
            "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123",
            mapOf("_dd.p.dm", "9bf3439f2f-1", "_dd.p.usr", "123")),
        Arguments.of(
            SAMPLER_KEEP,
            "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123",
            USER_DROP,
            MANUAL,
            "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123",
            mapOf("_dd.p.dm", "9bf3439f2f-1", "_dd.p.usr", "123")),
        Arguments.of(
            SAMPLER_KEEP,
            "_dd.p.usr=123",
            USER_KEEP,
            MANUAL,
            "_dd.p.usr=123",
            mapOf("_dd.p.usr", "123")));
  }

  @ParameterizedTest
  @MethodSource("forceKeepSpanPropagationTagsArguments")
  void forceKeepSpanPropagationTags(
      int priority, String header, String newHeader, Map<String, String> tagMap) throws Exception {
    tracer = tracerBuilder().writer(writer).build();
    PropagationTags propagationTags =
        tracer
            .getPropagationTagsFactory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, header);
    ExtractedContext extracted =
        (ExtractedContext)
            new ExtractedContext(
                    DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
                .withRequestContextDataAppSec("dummy");
    DDSpan span = (DDSpan) tracer.buildSpan("top").asChildOf(extracted).start();
    PropagationTags dd = span.context().getPropagationTags();

    span.context().forceKeep();

    assertEquals(newHeader, dd.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(tagMap, dd.createTagMap());
  }

  static Stream<Arguments> forceKeepSpanPropagationTagsArguments() {
    return Stream.of(
        Arguments.of(
            UNSET,
            "_dd.p.usr=123",
            "_dd.p.dm=-4,_dd.p.usr=123",
            mapOf("_dd.p.dm", "-4", "_dd.p.usr", "123")),
        Arguments.of(
            SAMPLER_KEEP,
            "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123",
            "_dd.p.dm=-4,_dd.p.usr=123",
            mapOf("_dd.p.dm", "-4", "_dd.p.usr", "123")),
        Arguments.of(
            SAMPLER_KEEP,
            "_dd.p.usr=123",
            "_dd.p.dm=-4,_dd.p.usr=123",
            mapOf("_dd.p.dm", "-4", "_dd.p.usr", "123")));
  }

  @ParameterizedTest
  @MethodSource("forceKeepTracePropagationTagsArguments")
  void forceKeepTracePropagationTags(
      int priority, String header, String rootHeader, Map<String, String> rootTagMap)
      throws Exception {
    tracer = tracerBuilder().writer(writer).build();
    PropagationTags propagationTags =
        tracer
            .getPropagationTagsFactory()
            .fromHeaderValue(PropagationTags.HeaderType.DATADOG, header);
    ExtractedContext extracted =
        (ExtractedContext)
            new ExtractedContext(
                    DDTraceId.from(123), 456, priority, "789", propagationTags, DATADOG)
                .withRequestContextDataAppSec("dummy");
    DDSpan rootSpan = (DDSpan) tracer.buildSpan("top").asChildOf(extracted).start();
    PropagationTags ddRoot = rootSpan.context().getPropagationTags();
    DDSpan span = (DDSpan) tracer.buildSpan("current").asChildOf(rootSpan.context()).start();

    span.context().forceKeep();

    assertEquals(rootHeader, ddRoot.headerValue(PropagationTags.HeaderType.DATADOG));
    assertEquals(rootTagMap, ddRoot.createTagMap());
  }

  static Stream<Arguments> forceKeepTracePropagationTagsArguments() {
    return Stream.of(
        Arguments.of(
            UNSET,
            "_dd.p.usr=123",
            "_dd.p.dm=-4,_dd.p.usr=123",
            mapOf("_dd.p.dm", "-4", "_dd.p.usr", "123")),
        Arguments.of(
            SAMPLER_KEEP,
            "_dd.p.dm=9bf3439f2f-1,_dd.p.usr=123",
            "_dd.p.dm=-4,_dd.p.usr=123",
            mapOf("_dd.p.dm", "-4", "_dd.p.usr", "123")),
        Arguments.of(
            SAMPLER_KEEP,
            "_dd.p.usr=123",
            "_dd.p.dm=-4,_dd.p.usr=123",
            mapOf("_dd.p.dm", "-4", "_dd.p.usr", "123")));
  }

  private static Map<String, String> mapOf(String... pairs) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      map.put(pairs[i], pairs[i + 1]);
    }
    return map;
  }
}
