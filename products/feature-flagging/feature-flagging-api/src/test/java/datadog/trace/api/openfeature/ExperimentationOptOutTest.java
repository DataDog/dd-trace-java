package datadog.trace.api.openfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.ufc.v1.Allocation;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.Value;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class ExperimentationOptOutTest {
  private static final String PROPERTY = "dd.feature.flags.experimentation.opt.out.mode";

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "disabled",
        "immediate",
        " IMMEDIATE ",
        "future_experiments_only",
        " FUTURE_EXPERIMENTS_ONLY ",
        "unsupported"
      })
  void providerConfigurationPrecedence(final String configured) {
    final String previous = System.getProperty(PROPERTY);
    System.setProperty(PROPERTY, configured);
    try {
      for (final ExperimentationOptOutMode override :
          new ExperimentationOptOutMode[] {
            ExperimentationOptOutMode.DISABLED,
            ExperimentationOptOutMode.IMMEDIATE,
            ExperimentationOptOutMode.FUTURE_EXPERIMENTS_ONLY
          }) {
        assertEquals(override, ExperimentationOptOut.resolve(override));
        new Provider(new Provider.Options().experimentationOptOutMode(override)).shutdown();
      }
      if ("unsupported".equals(configured)) {
        assertThrows(IllegalArgumentException.class, Provider::new);
      } else {
        final ExperimentationOptOutMode expected =
            configured.trim().equalsIgnoreCase("immediate")
                ? ExperimentationOptOutMode.IMMEDIATE
                : configured.trim().equalsIgnoreCase("future_experiments_only")
                    ? ExperimentationOptOutMode.FUTURE_EXPERIMENTS_ONLY
                    : ExperimentationOptOutMode.DISABLED;
        assertEquals(expected, ExperimentationOptOut.resolve(ExperimentationOptOutMode.DEFAULT));
        new Provider().shutdown();
      }
    } finally {
      if (previous == null) {
        System.clearProperty(PROPERTY);
      } else {
        System.setProperty(PROPERTY, previous);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-10-19T15:30:00Z",
        "2026-10-19T15:30:00.123456789Z",
        "2026-10-19T15:30:00+05:30",
        "2026-10-19T15:30:00-04:00",
        "9999-12-31T23:59:59Z",
        "2024-02-29T00:00:00Z"
      })
  void validTimestampProvidesCutoff(final String timestamp) {
    assertNotNull(
        ExperimentationOptOut.cutoff(
            ExperimentationOptOutMode.IMMEDIATE,
            new MutableContext().add(ExperimentationOptOut.ATTRIBUTE, timestamp)));
  }

  static Stream<Arguments> invalidValues() {
    return Stream.of(
            new Value(),
            new Value(""),
            new Value("not a timestamp"),
            new Value("2026-10-19"),
            new Value("2026-10-19T15:30:00"),
            new Value("2026-02-30T00:00:00Z"),
            new Value("2026-10-19T24:00:00Z"),
            new Value("2026-10-19T15:30:60Z"),
            new Value("2026-10-19T15:30:00+99:00"),
            new Value(" 2026-10-19T15:30:00Z"),
            new Value(true),
            new Value(42))
        .map(Arguments::of);
  }

  @ParameterizedTest
  @MethodSource("invalidValues")
  void invalidValuesAreRejectedOnlyWhenEnabled(final Value value) {
    final MutableContext context =
        new MutableContext(Collections.singletonMap(ExperimentationOptOut.ATTRIBUTE, value));
    for (final ExperimentationOptOutMode mode :
        new ExperimentationOptOutMode[] {
          ExperimentationOptOutMode.IMMEDIATE, ExperimentationOptOutMode.FUTURE_EXPERIMENTS_ONLY
        }) {
      final IllegalArgumentException error =
          assertThrows(
              IllegalArgumentException.class, () -> ExperimentationOptOut.cutoff(mode, context));
      assertEquals(ExperimentationOptOut.INVALID_TIMESTAMP, error.getMessage());
    }
    assertNull(ExperimentationOptOut.cutoff(ExperimentationOptOutMode.DISABLED, context));
  }

  @Test
  void missingAttributePreservesParticipation() {
    for (final ExperimentationOptOutMode mode : ExperimentationOptOutMode.values()) {
      assertNull(ExperimentationOptOut.cutoff(mode, new MutableContext()));
      // An inactive cutoff must return without inspecting the allocation.
      assertFalse(ExperimentationOptOut.excludes(mode, null, null));
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-10-19T15:30:00Z",
        "2026-10-19T15:30:00.123456789Z",
        "2026-10-19T15:30:00.123456789123Z",
        "2026-10-19T15:30:00+05:30",
        "2026-10-19T15:30:00+24:00",
        "2026-10-19T15:30:00+00:60",
        "2026-10-19T15:30:60Z",
        "2026-02-30T00:00:00Z",
        "2026-10-19t15:30:00z",
        "2026-10-19T5:30:00Z",
        "2026-10-19"
      })
  void timestampValidationMatchesAllocationTimestamps(final String timestamp) {
    boolean accepted;
    try {
      DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(timestamp, Instant::from);
      accepted = true;
    } catch (final DateTimeException e) {
      accepted = false;
    }
    final MutableContext context =
        new MutableContext().add(ExperimentationOptOut.ATTRIBUTE, timestamp);
    if (accepted) {
      assertNotNull(ExperimentationOptOut.cutoff(ExperimentationOptOutMode.IMMEDIATE, context));
    } else {
      assertThrows(
          IllegalArgumentException.class,
          () -> ExperimentationOptOut.cutoff(ExperimentationOptOutMode.IMMEDIATE, context));
    }
  }

  @TableTest({
    "mode                    | doLog | activation                          | excluded",
    "IMMEDIATE               | true  | 2026-10-19T11:59:59.999999999Z      | true    ",
    "FUTURE_EXPERIMENTS_ONLY | true  | 2026-10-19T11:59:59.999999999Z      | false   ",
    "FUTURE_EXPERIMENTS_ONLY | true  | 2026-10-19T12:00:00Z                | true    ",
    "FUTURE_EXPERIMENTS_ONLY | true  | 2026-10-19T12:00:00.000000001Z      | true    ",
    "FUTURE_EXPERIMENTS_ONLY | true  | 2026-10-19T08:00:00-04:00           | true    ",
    "FUTURE_EXPERIMENTS_ONLY | true  | 2026-10-19T07:59:59.999999999-04:00 | false   ",
    "FUTURE_EXPERIMENTS_ONLY | true  |                                     | true    ",
    "FUTURE_EXPERIMENTS_ONLY | false |                                     | false   ",
    "IMMEDIATE               | false |                                     | false   "
  })
  void appliesActivationCutoff(
      final ExperimentationOptOutMode mode,
      final boolean doLog,
      final String activation,
      final boolean excluded) {
    final Allocation allocation =
        Allocation.fromInstants(
            "allocation",
            null,
            null,
            null,
            Collections.emptyList(),
            doLog,
            activation == null
                ? null
                : DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(activation, Instant::from));
    assertEquals(
        excluded,
        ExperimentationOptOut.excludes(mode, Instant.parse("2026-10-19T12:00:00Z"), allocation));
  }

  @Test
  void nullProgrammaticModeIsRejected() {
    assertThrows(
        NullPointerException.class, () -> new Provider.Options().experimentationOptOutMode(null));
  }

  @Test
  void probesActivationSupportWithoutRequiringItOnOlderAgents() {
    assertTrue(ExperimentationOptOut.supportsActivation(Allocation.class));
    assertFalse(ExperimentationOptOut.supportsActivation(Object.class));
    assertFalse(ExperimentationOptOut.supportsActivation(WrongActivationType.class));
  }

  public static class WrongActivationType {
    public String randomizationActivatedAt;
  }
}
