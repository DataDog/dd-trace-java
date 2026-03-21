package datadog.trace.common.sampling;

import static datadog.trace.api.sampling.PrioritySampling.*;
import static datadog.trace.api.sampling.SamplingMechanism.*;
import static org.junit.jupiter.api.Assertions.*;

import datadog.trace.api.DDTags;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.test.DDCoreSpecification;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class ForcePrioritySamplerTest extends DDCoreSpecification {

  @ParameterizedTest
  @MethodSource("forcePrioritySamplingArguments")
  void forcePrioritySampling(int prioritySampling, int samplingMechanism, int expectedSampling) {
    ListWriter writer = new ListWriter();
    ForcePrioritySampler sampler = new ForcePrioritySampler(prioritySampling, samplingMechanism);
    CoreTracer tracer = tracerBuilder().writer(writer).sampler(sampler).build();
    try {
      DDSpan span1 = (DDSpan) tracer.buildSpan("test").start();
      sampler.setSamplingPriority(span1);

      assertEquals(expectedSampling, span1.getSamplingPriority().intValue());
      assertTrue(sampler.sample(span1));
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> forcePrioritySamplingArguments() {
    return Stream.of(
        Arguments.of(SAMPLER_KEEP, DEFAULT, SAMPLER_KEEP),
        Arguments.of(SAMPLER_DROP, DEFAULT, SAMPLER_DROP),
        Arguments.of(SAMPLER_KEEP, AGENT_RATE, SAMPLER_KEEP),
        Arguments.of(SAMPLER_DROP, AGENT_RATE, SAMPLER_DROP),
        Arguments.of(SAMPLER_KEEP, REMOTE_AUTO_RATE, SAMPLER_KEEP),
        Arguments.of(SAMPLER_DROP, REMOTE_AUTO_RATE, SAMPLER_DROP));
  }

  @ParameterizedTest
  @MethodSource("samplingPrioritySetArguments")
  void samplingPrioritySet(int prioritySampling, int samplingMechanism, int expectedSampling)
      throws Exception {
    ListWriter writer = new ListWriter();
    ForcePrioritySampler sampler = new ForcePrioritySampler(prioritySampling, samplingMechanism);
    CoreTracer tracer = tracerBuilder().writer(writer).sampler(sampler).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("test").start();

      assertNull(span.getSamplingPriority());

      span.setTag(DDTags.SERVICE_NAME, "spock");

      span.finish();
      writer.waitForTraces(1);
      assertEquals(expectedSampling, span.getSamplingPriority().intValue());
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> samplingPrioritySetArguments() {
    return Stream.of(
        Arguments.of(SAMPLER_KEEP, DEFAULT, SAMPLER_KEEP),
        Arguments.of(SAMPLER_DROP, DEFAULT, SAMPLER_DROP));
  }

  @ParameterizedTest
  @MethodSource("settingForcedTracingViaTagArguments")
  void settingForcedTracingViaTag(String tagName, boolean tagValue, int expectedPriority) {
    ForcePrioritySampler sampler = new ForcePrioritySampler(SAMPLER_KEEP, DEFAULT);
    CoreTracer tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("root").start();
      if (tagName != null) {
        span.setTag(tagName, tagValue);
      }
      span.finish();

      assertEquals(expectedPriority, span.getSamplingPriority().intValue());
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> settingForcedTracingViaTagArguments() {
    return Stream.of(
        Arguments.of("manual.drop", true, USER_DROP), Arguments.of("manual.keep", true, USER_KEEP));
  }

  @ParameterizedTest
  @MethodSource("notSettingForcedTracingViaTagArguments")
  void notSettingForcedTracingViaTagOrSettingItWrongValueNotCausingException(
      String tagName, Object tagValue) {
    ForcePrioritySampler sampler = new ForcePrioritySampler(SAMPLER_KEEP, DEFAULT);
    CoreTracer tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("root").start();
      if (tagName != null) {
        if (tagValue instanceof Boolean) {
          span.setTag(tagName, (Boolean) tagValue);
        } else if (tagValue instanceof Number) {
          span.setTag(tagName, (Number) tagValue);
        } else {
          span.setTag(tagName, String.valueOf(tagValue));
        }
      }

      assertNull(span.getSamplingPriority());

      span.finish();
    } finally {
      tracer.close();
    }
  }

  static Stream<Arguments> notSettingForcedTracingViaTagArguments() {
    return Stream.of(
        Arguments.of(null, null),
        Arguments.of("manual.drop", false),
        Arguments.of("manual.keep", false),
        Arguments.of("manual.drop", 1),
        Arguments.of("manual.keep", 1));
  }
}
