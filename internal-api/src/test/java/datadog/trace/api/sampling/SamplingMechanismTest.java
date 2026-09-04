package datadog.trace.api.sampling;

import static datadog.trace.api.config.GeneralConfig.APM_TRACING_ENABLED;
import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_KEEP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.api.sampling.SamplingMechanism.AGENT_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.APPSEC;
import static datadog.trace.api.sampling.SamplingMechanism.DATA_JOBS;
import static datadog.trace.api.sampling.SamplingMechanism.DATA_STREAMS;
import static datadog.trace.api.sampling.SamplingMechanism.DEFAULT;
import static datadog.trace.api.sampling.SamplingMechanism.EXTERNAL_OVERRIDE;
import static datadog.trace.api.sampling.SamplingMechanism.LOCAL_USER_RULE;
import static datadog.trace.api.sampling.SamplingMechanism.MANUAL;
import static datadog.trace.api.sampling.SamplingMechanism.REMOTE_AUTO_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.REMOTE_USER_RATE;
import static datadog.trace.api.sampling.SamplingMechanism.UNKNOWN;
import static datadog.trace.api.sampling.SamplingMechanism.canAvoidSamplingPriorityLock;
import static datadog.trace.api.sampling.SamplingMechanism.validateWithSamplingPriority;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@ExtendWith(WithConfigExtension.class)
class SamplingMechanismTest {

  // one below USER_DROP / one above USER_KEEP: neither a valid sampler nor user priority value
  private static final byte USER_DROP_X = (byte) (USER_DROP - 1);
  private static final byte USER_KEEP_X = (byte) (USER_KEEP + 1);

  @ParameterizedTest
  @MethodSource("testValidationArguments")
  void testValidation(byte mechanism, byte priority, boolean valid) {
    assertEquals(valid, validateWithSamplingPriority(mechanism, priority));
  }

  private static Stream<Arguments> testValidationArguments() {
    return Stream.of(
        Arguments.of(UNKNOWN, UNSET, true),
        Arguments.of(UNKNOWN, SAMPLER_DROP, true),
        Arguments.of(UNKNOWN, SAMPLER_KEEP, true),
        Arguments.of(UNKNOWN, USER_DROP, true),
        Arguments.of(UNKNOWN, USER_KEEP, true),
        Arguments.of(UNKNOWN, USER_DROP_X, true),
        Arguments.of(UNKNOWN, USER_KEEP_X, true),
        Arguments.of(DEFAULT, UNSET, false),
        Arguments.of(DEFAULT, SAMPLER_DROP, true),
        Arguments.of(DEFAULT, SAMPLER_KEEP, true),
        Arguments.of(DEFAULT, USER_DROP, false),
        Arguments.of(DEFAULT, USER_KEEP, false),
        Arguments.of(DEFAULT, USER_DROP_X, false),
        Arguments.of(DEFAULT, USER_KEEP_X, false),
        Arguments.of(AGENT_RATE, UNSET, false),
        Arguments.of(AGENT_RATE, SAMPLER_DROP, true),
        Arguments.of(AGENT_RATE, SAMPLER_KEEP, true),
        Arguments.of(AGENT_RATE, USER_DROP, false),
        Arguments.of(AGENT_RATE, USER_KEEP, false),
        Arguments.of(AGENT_RATE, USER_DROP_X, false),
        Arguments.of(AGENT_RATE, USER_KEEP_X, false),
        Arguments.of(REMOTE_AUTO_RATE, UNSET, false),
        Arguments.of(REMOTE_AUTO_RATE, SAMPLER_DROP, true),
        Arguments.of(REMOTE_AUTO_RATE, SAMPLER_KEEP, true),
        Arguments.of(REMOTE_AUTO_RATE, USER_DROP, false),
        Arguments.of(REMOTE_AUTO_RATE, USER_KEEP, false),
        Arguments.of(REMOTE_AUTO_RATE, USER_DROP_X, false),
        Arguments.of(REMOTE_AUTO_RATE, USER_KEEP_X, false),
        Arguments.of(LOCAL_USER_RULE, UNSET, false),
        Arguments.of(LOCAL_USER_RULE, SAMPLER_DROP, false),
        Arguments.of(LOCAL_USER_RULE, SAMPLER_KEEP, false),
        Arguments.of(LOCAL_USER_RULE, USER_DROP, true),
        Arguments.of(LOCAL_USER_RULE, USER_KEEP, true),
        Arguments.of(LOCAL_USER_RULE, USER_DROP_X, false),
        Arguments.of(LOCAL_USER_RULE, USER_KEEP_X, false),
        Arguments.of(MANUAL, UNSET, false),
        Arguments.of(MANUAL, SAMPLER_DROP, false),
        Arguments.of(MANUAL, SAMPLER_KEEP, false),
        Arguments.of(MANUAL, USER_DROP, true),
        Arguments.of(MANUAL, USER_KEEP, true),
        Arguments.of(MANUAL, USER_DROP_X, false),
        Arguments.of(MANUAL, USER_KEEP_X, false),
        Arguments.of(REMOTE_USER_RATE, UNSET, false),
        Arguments.of(REMOTE_USER_RATE, SAMPLER_DROP, false),
        Arguments.of(REMOTE_USER_RATE, SAMPLER_KEEP, false),
        Arguments.of(REMOTE_USER_RATE, USER_DROP, true),
        Arguments.of(REMOTE_USER_RATE, USER_KEEP, true),
        Arguments.of(REMOTE_USER_RATE, USER_DROP_X, false),
        Arguments.of(REMOTE_USER_RATE, USER_KEEP_X, false),
        Arguments.of(APPSEC, UNSET, false),
        Arguments.of(APPSEC, SAMPLER_DROP, true),
        Arguments.of(APPSEC, SAMPLER_KEEP, true),
        Arguments.of(APPSEC, USER_DROP, false),
        Arguments.of(APPSEC, USER_KEEP, true),
        Arguments.of(APPSEC, USER_DROP_X, false),
        Arguments.of(APPSEC, USER_KEEP_X, false),
        Arguments.of(DATA_JOBS, UNSET, false),
        Arguments.of(DATA_JOBS, SAMPLER_DROP, false),
        Arguments.of(DATA_JOBS, SAMPLER_KEEP, false),
        Arguments.of(DATA_JOBS, USER_DROP, false),
        Arguments.of(DATA_JOBS, USER_KEEP, true),
        Arguments.of(DATA_JOBS, USER_DROP_X, false),
        Arguments.of(DATA_JOBS, USER_KEEP_X, false),
        Arguments.of(DATA_STREAMS, UNSET, false),
        Arguments.of(DATA_STREAMS, SAMPLER_DROP, false),
        Arguments.of(DATA_STREAMS, SAMPLER_KEEP, false),
        Arguments.of(DATA_STREAMS, USER_DROP, true),
        Arguments.of(DATA_STREAMS, USER_KEEP, false),
        Arguments.of(DATA_STREAMS, USER_DROP_X, false),
        Arguments.of(DATA_STREAMS, USER_KEEP_X, false),
        Arguments.of(EXTERNAL_OVERRIDE, UNSET, false),
        Arguments.of(EXTERNAL_OVERRIDE, SAMPLER_DROP, false),
        Arguments.of(EXTERNAL_OVERRIDE, SAMPLER_KEEP, false),
        Arguments.of(EXTERNAL_OVERRIDE, USER_DROP, false),
        Arguments.of(EXTERNAL_OVERRIDE, USER_KEEP, false),
        Arguments.of(EXTERNAL_OVERRIDE, USER_DROP_X, false),
        Arguments.of(EXTERNAL_OVERRIDE, USER_KEEP_X, false));
  }

  @ParameterizedTest
  @MethodSource("testCanAvoidSamplingPriorityLockArguments")
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void testCanAvoidSamplingPriorityLock(byte mechanism, byte priority, boolean valid) {
    assertEquals(valid, canAvoidSamplingPriorityLock(priority, mechanism));
  }

  private static Stream<Arguments> testCanAvoidSamplingPriorityLockArguments() {
    return Stream.of(
        Arguments.of(APPSEC, UNSET, true),
        Arguments.of(APPSEC, SAMPLER_KEEP, true),
        Arguments.of(UNKNOWN, SAMPLER_KEEP, false),
        Arguments.of(DEFAULT, SAMPLER_KEEP, false),
        Arguments.of(AGENT_RATE, SAMPLER_KEEP, false),
        Arguments.of(REMOTE_AUTO_RATE, SAMPLER_KEEP, false),
        Arguments.of(LOCAL_USER_RULE, SAMPLER_KEEP, false),
        Arguments.of(MANUAL, SAMPLER_KEEP, false),
        Arguments.of(REMOTE_USER_RATE, SAMPLER_KEEP, false),
        Arguments.of(DATA_JOBS, SAMPLER_KEEP, false),
        // DSM is left at its config default (disabled) for this parameterized run, so the
        // DATA_STREAMS case is false here for a config reason. The two dedicated tests below
        // cover the mechanism itself with data.streams.enabled explicitly set both ways.
        Arguments.of(DATA_STREAMS, SAMPLER_KEEP, false),
        Arguments.of(EXTERNAL_OVERRIDE, SAMPLER_KEEP, false));
  }

  @Test
  @WithConfig(key = DATA_STREAMS_ENABLED, value = "true")
  void dataStreamsMechanismCanAvoidSamplingPriorityLockWhenDataStreamsEnabled() {
    // The DATA_STREAMS case is priority-independent: the mechanism alone unlocks the priority.
    assertTrue(canAvoidSamplingPriorityLock(USER_DROP, DATA_STREAMS));
    assertTrue(canAvoidSamplingPriorityLock(SAMPLER_KEEP, DATA_STREAMS));
    assertTrue(canAvoidSamplingPriorityLock(UNSET, DATA_STREAMS));
    // Enabling DSM must not unlock any other mechanism.
    assertFalse(canAvoidSamplingPriorityLock(USER_DROP, MANUAL));
    assertFalse(canAvoidSamplingPriorityLock(USER_DROP, DEFAULT));
  }

  @Test
  @WithConfig(key = DATA_STREAMS_ENABLED, value = "false")
  void dataStreamsMechanismCannotAvoidSamplingPriorityLockWhenDataStreamsDisabled() {
    assertFalse(canAvoidSamplingPriorityLock(USER_DROP, DATA_STREAMS));
    assertFalse(canAvoidSamplingPriorityLock(SAMPLER_KEEP, DATA_STREAMS));
  }
}
