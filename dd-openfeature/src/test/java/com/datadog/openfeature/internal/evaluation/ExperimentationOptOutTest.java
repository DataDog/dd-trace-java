package com.datadog.openfeature.internal.evaluation;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datadog.openfeature.ExperimentationOptOutMode;
import com.datadog.openfeature.Provider;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.Settings;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.Value;
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
        assertEquals(
            override, ExperimentationOptOut.resolve(override, Settings.load(AgentBridge.NONE)));
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
        assertEquals(
            expected,
            ExperimentationOptOut.resolve(
                ExperimentationOptOutMode.DEFAULT, Settings.load(AgentBridge.NONE)));
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
            new Value(Instant.parse("2026-10-19T12:00:00Z")),
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
      assertEquals(
          "dd_experimentation_opt_out_at must be an ISO offset date-time string;"
              + " supply a valid timestamp to opt out, or omit the attribute to permit experimentation",
          error.getMessage());
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

  @TableTest({
    "timestamp                         | accepted",
    "2026-10-19T15:30:00Z              | true    ",
    "2026-10-19T15:30:00.123456789Z    | true    ",
    "2026-10-19T15:30:00.123456789123Z | false   ",
    "2026-10-19T15:30:00+05:30         | true    ",
    "2026-10-19T15:30:00+18:00         | true    ",
    "2026-10-19T15:30:00+18:01         | false   ",
    "2026-10-19T15:30:00+24:00         | false   ",
    "2026-10-19T15:30:00+00:60         | false   ",
    "2026-10-19T15:30:60Z              | false   ",
    "2026-02-30T00:00:00Z              | false   ",
    "2024-02-29T00:00:00Z              | true    ",
    "2026-10-19t15:30:00z              | true    ",
    "2026-10-19T5:30:00Z               | false   ",
    "2026-10-19                        | false   ",
    "2026-10-19T15:30:00               | false   ",
    "2026-10-19T15:30Z                 | true    ",
    "2026-10-19T15:30:00.Z             | true    ",
    "2026-10-19T15:30:00+05            | true    ",
    "2026-10-19T15:30:00+05:30:15      | true    ",
    "+10000-10-19T15:30:00Z            | true    ",
    "' 2026-10-19T15:30:00Z'           | false   ",
    "2026-10-19T24:00:00Z              | false   "
  })
  void timestampDialectHasIndependentExpectations(final String timestamp, final boolean accepted)
      throws Exception {
    final MutableContext context =
        new MutableContext().add(ExperimentationOptOut.ATTRIBUTE, timestamp);
    if (accepted) {
      assertNotNull(ExperimentationOptOut.cutoff(ExperimentationOptOutMode.IMMEDIATE, context));
    } else {
      assertThrows(
          ExperimentationOptOut.InvalidTimestampException.class,
          () -> ExperimentationOptOut.cutoff(ExperimentationOptOutMode.IMMEDIATE, context));
    }
    final String json =
        "{\"flags\":{\"flag\":{\"allocations\":[{\"randomizationActivatedAt\":\""
            + timestamp
            + "\"}]}}}";
    final Allocation allocation =
        UniversalFlagConfigParser.parse(json.getBytes(UTF_8)).flags.get("flag").allocations.get(0);
    assertEquals(!accepted, allocation.invalidRandomizationActivation);
    assertEquals(accepted, allocation.randomizationActivatedAt != null);
  }

  @Test
  void rawNullEntryIsPresentAndInvalid() {
    final MutableContext context =
        new MutableContext(Collections.singletonMap(ExperimentationOptOut.ATTRIBUTE, null));
    assertThrows(
        ExperimentationOptOut.InvalidTimestampException.class,
        () -> ExperimentationOptOut.cutoff(ExperimentationOptOutMode.IMMEDIATE, context));
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
        new Allocation(
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
}
