package datadog.trace.api.sampling;

import static datadog.trace.api.config.GeneralConfig.APM_TRACING_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_JOBS_ENABLED;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.canAvoidSamplingPriorityLock;
import static datadog.trace.api.sampling.SamplingMechanism.validateWithSamplingPriority;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.converter.PrioritySamplingConverter;
import datadog.trace.test.junit.utils.converter.SamplingMechanismConverter;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ConvertWith;
import org.tabletest.junit.TableTest;

class SamplingMechanismTest extends DDJavaSpecification {

  @TableTest({
    "mechanism         | priority     | valid",
    "UNKNOWN           | UNSET        | true ",
    "UNKNOWN           | SAMPLER_DROP | true ",
    "UNKNOWN           | SAMPLER_KEEP | true ",
    "UNKNOWN           | USER_DROP    | true ",
    "UNKNOWN           | USER_KEEP    | true ",
    "UNKNOWN           | USER_DROP-1  | true ",
    "UNKNOWN           | USER_KEEP+1  | true ",
    "DEFAULT           | UNSET        | false",
    "DEFAULT           | SAMPLER_DROP | true ",
    "DEFAULT           | SAMPLER_KEEP | true ",
    "DEFAULT           | USER_DROP    | false",
    "DEFAULT           | USER_KEEP    | false",
    "DEFAULT           | USER_DROP-1  | false",
    "DEFAULT           | USER_KEEP+1  | false",
    "AGENT_RATE        | UNSET        | false",
    "AGENT_RATE        | SAMPLER_DROP | true ",
    "AGENT_RATE        | SAMPLER_KEEP | true ",
    "AGENT_RATE        | USER_DROP    | false",
    "AGENT_RATE        | USER_KEEP    | false",
    "AGENT_RATE        | USER_DROP-1  | false",
    "AGENT_RATE        | USER_KEEP+1  | false",
    "REMOTE_AUTO_RATE  | UNSET        | false",
    "REMOTE_AUTO_RATE  | SAMPLER_DROP | true ",
    "REMOTE_AUTO_RATE  | SAMPLER_KEEP | true ",
    "REMOTE_AUTO_RATE  | USER_DROP    | false",
    "REMOTE_AUTO_RATE  | USER_KEEP    | false",
    "REMOTE_AUTO_RATE  | USER_DROP-1  | false",
    "REMOTE_AUTO_RATE  | USER_KEEP+1  | false",
    "LOCAL_USER_RULE   | UNSET        | false",
    "LOCAL_USER_RULE   | SAMPLER_DROP | false",
    "LOCAL_USER_RULE   | SAMPLER_KEEP | false",
    "LOCAL_USER_RULE   | USER_DROP    | true ",
    "LOCAL_USER_RULE   | USER_KEEP    | true ",
    "LOCAL_USER_RULE   | USER_DROP-1  | false",
    "LOCAL_USER_RULE   | USER_KEEP+1  | false",
    "MANUAL            | UNSET        | false",
    "MANUAL            | SAMPLER_DROP | false",
    "MANUAL            | SAMPLER_KEEP | false",
    "MANUAL            | USER_DROP    | true ",
    "MANUAL            | USER_KEEP    | true ",
    "MANUAL            | USER_DROP-1  | false",
    "MANUAL            | USER_KEEP+1  | false",
    "REMOTE_USER_RATE  | UNSET        | false",
    "REMOTE_USER_RATE  | SAMPLER_DROP | false",
    "REMOTE_USER_RATE  | SAMPLER_KEEP | false",
    "REMOTE_USER_RATE  | USER_DROP    | true ",
    "REMOTE_USER_RATE  | USER_KEEP    | true ",
    "REMOTE_USER_RATE  | USER_DROP-1  | false",
    "REMOTE_USER_RATE  | USER_KEEP+1  | false",
    "APPSEC            | UNSET        | false",
    "APPSEC            | SAMPLER_DROP | true ",
    "APPSEC            | SAMPLER_KEEP | true ",
    "APPSEC            | USER_DROP    | false",
    "APPSEC            | USER_KEEP    | true ",
    "APPSEC            | USER_DROP-1  | false",
    "APPSEC            | USER_KEEP+1  | false",
    "DATA_JOBS         | UNSET        | false",
    "DATA_JOBS         | SAMPLER_DROP | false",
    "DATA_JOBS         | SAMPLER_KEEP | false",
    "DATA_JOBS         | USER_DROP    | false",
    "DATA_JOBS         | USER_KEEP    | true ",
    "DATA_JOBS         | USER_DROP-1  | false",
    "DATA_JOBS         | USER_KEEP+1  | false",
    "EXTERNAL_OVERRIDE | UNSET        | false",
    "EXTERNAL_OVERRIDE | SAMPLER_DROP | false",
    "EXTERNAL_OVERRIDE | SAMPLER_KEEP | false",
    "EXTERNAL_OVERRIDE | USER_DROP    | false",
    "EXTERNAL_OVERRIDE | USER_KEEP    | false",
    "EXTERNAL_OVERRIDE | USER_DROP-1  | false",
    "EXTERNAL_OVERRIDE | USER_KEEP+1  | false"
  })
  void testValidation(
      @ConvertWith(SamplingMechanismConverter.class) byte mechanism,
      @ConvertWith(OutOfRangePrioritySamplingConverter.class) byte priority,
      boolean valid) {
    assertEquals(valid, validateWithSamplingPriority(mechanism, priority));
  }

  @TableTest({
    "mechanism         | priority     | valid",
    "APPSEC            | UNSET        | true ",
    "APPSEC            | SAMPLER_KEEP | true ",
    "UNKNOWN           | SAMPLER_KEEP | false",
    "DEFAULT           | SAMPLER_DROP | true ",
    "DEFAULT           | SAMPLER_KEEP | false",
    "AGENT_RATE        | SAMPLER_KEEP | false",
    "REMOTE_AUTO_RATE  | SAMPLER_KEEP | false",
    "LOCAL_USER_RULE   | SAMPLER_KEEP | false",
    "MANUAL            | SAMPLER_KEEP | false",
    "REMOTE_USER_RATE  | SAMPLER_KEEP | false",
    "DATA_JOBS         | SAMPLER_KEEP | false",
    "EXTERNAL_OVERRIDE | SAMPLER_KEEP | false"
  })
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void testCanAvoidSamplingPriorityLock(
      @ConvertWith(SamplingMechanismConverter.class) byte mechanism,
      @ConvertWith(PrioritySamplingConverter.class) byte priority,
      boolean valid) {
    assertEquals(valid, canAvoidSamplingPriorityLock(priority, mechanism));
  }

  @TableTest({
    "mechanism | priority     | valid",
    "DEFAULT   | SAMPLER_DROP | false",
    "APPSEC    | SAMPLER_KEEP | true ",
    "DATA_JOBS | USER_KEEP    | true "
  })
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  @WithConfig(key = DATA_JOBS_ENABLED, value = "true")
  void testCanAvoidSamplingPriorityLockWithDataJobsEnabled(
      @ConvertWith(SamplingMechanismConverter.class) byte mechanism,
      @ConvertWith(PrioritySamplingConverter.class) byte priority,
      boolean valid) {
    assertEquals(valid, canAvoidSamplingPriorityLock(priority, mechanism));
  }

  @TableTest({
    "mechanism | priority     | valid",
    "DEFAULT   | SAMPLER_DROP | false",
    "APPSEC    | SAMPLER_KEEP | false"
  })
  void testCanAvoidSamplingPriorityLockWithApmTracingEnabled(
      @ConvertWith(SamplingMechanismConverter.class) byte mechanism,
      @ConvertWith(PrioritySamplingConverter.class) byte priority,
      boolean valid) {
    assertEquals(valid, canAvoidSamplingPriorityLock(priority, mechanism));
  }

  /** Also resolves the out-of-range priorities just below USER_DROP and just above USER_KEEP. */
  static class OutOfRangePrioritySamplingConverter extends PrioritySamplingConverter {
    @Override
    protected boolean throwsOnUnsupportedValue() {
      return false;
    }

    @Override
    protected Byte convertWhenNoMapping(Object source) {
      switch (source.toString()) {
        case "USER_DROP-1":
          return USER_DROP - 1;
        case "USER_KEEP+1":
          return USER_KEEP + 1;
        default:
          throw new ArgumentConversionException("Unsupported priority " + source);
      }
    }
  }
}
