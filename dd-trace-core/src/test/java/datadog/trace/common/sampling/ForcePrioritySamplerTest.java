package datadog.trace.common.sampling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.DDTags;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.test.junit.utils.converter.SamplingMechanismConverter;
import datadog.trace.test.junit.utils.tabletest.BoxedValueConverter;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class ForcePrioritySamplerTest extends DDCoreJavaSpecification {

  private final ListWriter writer = new ListWriter();

  @TableTest({
    "scenario             | prioritySampling              | samplingMechanism                  | expectedSampling             ",
    "SAMPLER_KEEP DEFAULT | PrioritySampling.SAMPLER_KEEP | SamplingMechanism.DEFAULT          | PrioritySampling.SAMPLER_KEEP",
    "SAMPLER_DROP DEFAULT | PrioritySampling.SAMPLER_DROP | SamplingMechanism.DEFAULT          | PrioritySampling.SAMPLER_DROP",
    "SAMPLER_KEEP AGENT   | PrioritySampling.SAMPLER_KEEP | SamplingMechanism.AGENT_RATE       | PrioritySampling.SAMPLER_KEEP",
    "SAMPLER_DROP AGENT   | PrioritySampling.SAMPLER_DROP | SamplingMechanism.AGENT_RATE       | PrioritySampling.SAMPLER_DROP",
    "SAMPLER_KEEP REMOTE  | PrioritySampling.SAMPLER_KEEP | SamplingMechanism.REMOTE_AUTO_RATE | PrioritySampling.SAMPLER_KEEP",
    "SAMPLER_DROP REMOTE  | PrioritySampling.SAMPLER_DROP | SamplingMechanism.REMOTE_AUTO_RATE | PrioritySampling.SAMPLER_DROP"
  })
  void forcePrioritySampling(
      @ConvertWith(PrioritySamplingConverter.class) int prioritySampling,
      @ConvertWith(SamplingMechanismConverter.class) int samplingMechanism,
      @ConvertWith(PrioritySamplingConverter.class) int expectedSampling) {
    ForcePrioritySampler sampler = new ForcePrioritySampler(prioritySampling, samplingMechanism);
    CoreTracer tracer = tracerBuilder().writer(writer).sampler(sampler).build();
    try {
      DDSpan span1 = (DDSpan) tracer.buildSpan("datadog", "test").start();
      sampler.setSamplingPriority(span1);

      assertEquals(expectedSampling, span1.getSamplingPriority());
      assertTrue(sampler.sample(span1));
    } finally {
      tracer.close();
    }
  }

  @TableTest({
    "scenario             | prioritySampling              | samplingMechanism         | expectedSampling             ",
    "SAMPLER_KEEP DEFAULT | PrioritySampling.SAMPLER_KEEP | SamplingMechanism.DEFAULT | PrioritySampling.SAMPLER_KEEP",
    "SAMPLER_DROP DEFAULT | PrioritySampling.SAMPLER_DROP | SamplingMechanism.DEFAULT | PrioritySampling.SAMPLER_DROP"
  })
  void samplingPrioritySet(
      @ConvertWith(PrioritySamplingConverter.class) int prioritySampling,
      @ConvertWith(SamplingMechanismConverter.class) int samplingMechanism,
      @ConvertWith(PrioritySamplingConverter.class) int expectedSampling)
      throws Exception {
    ForcePrioritySampler sampler = new ForcePrioritySampler(prioritySampling, samplingMechanism);
    CoreTracer tracer = tracerBuilder().writer(writer).sampler(sampler).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("datadog", "test").start();

      assertNull(span.getSamplingPriority());

      span.setTag(DDTags.SERVICE_NAME, "spock");

      span.finish();
      writer.waitForTraces(1);
      assertEquals(expectedSampling, span.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }

  @TableTest({
    "scenario         | tagName     | tagValue | expectedPriority          ",
    "manual.drop true | manual.drop | true     | PrioritySampling.USER_DROP",
    "manual.keep true | manual.keep | true     | PrioritySampling.USER_KEEP"
  })
  void settingForcedTracingViaTag(
      String tagName,
      boolean tagValue,
      @ConvertWith(PrioritySamplingConverter.class) int expectedPriority) {
    ForcePrioritySampler sampler =
        new ForcePrioritySampler(PrioritySampling.SAMPLER_KEEP, SamplingMechanism.DEFAULT);
    CoreTracer tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("datadog", "root").start();
      span.setTag(tagName, tagValue);
      span.finish();

      assertEquals(expectedPriority, span.getSamplingPriority());
    } finally {
      tracer.close();
    }
  }

  @TableTest({
    "scenario          | tagName     | tagValue",
    "no tag            |             |         ",
    "manual.drop false | manual.drop | false   ",
    "manual.keep false | manual.keep | false   ",
    "manual.drop 1     | manual.drop | 1       ",
    "manual.keep 1     | manual.keep | 1       "
  })
  void notSettingForcedTracingViaTagOrSettingItWrongValueNotCausingException(
      String tagName, @ConvertWith(BoxedValueConverter.class) Object tagValue) {
    ForcePrioritySampler sampler =
        new ForcePrioritySampler(PrioritySampling.SAMPLER_KEEP, SamplingMechanism.DEFAULT);
    CoreTracer tracer = tracerBuilder().writer(new LoggingWriter()).sampler(sampler).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("datadog", "root").start();
      if (tagName != null) {
        span.setTag(tagName, tagValue);
      }

      assertNull(span.getSamplingPriority());

      span.finish();
    } finally {
      tracer.close();
    }
  }
}
