package com.datadog.openfeature.internal.evaluation;

import static com.datadog.openfeature.internal.ufc.ConditionOperator.IS_NULL;
import static com.datadog.openfeature.internal.ufc.ConditionOperator.ONE_OF;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.ConditionOperator;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.Rule;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.UniversalFlagConfigParser;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import com.datadog.openfeature.testlog.CapturingServiceProvider;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

/** Evaluates flags through {@link FlagEvaluator} to check what {@link EvaluationScope} reads. */
class EvaluationScopeFlagTest {
  private static final String WARNING = "Ignoring unknown feature destination";
  private static final int ATTRIBUTES = 12;

  @BeforeEach
  void setUp() {
    SplitFeatures.WARNED_FEATURE_DESTINATIONS.clear();
    CapturingServiceProvider.reset();
  }

  @AfterEach
  void tearDown() {
    SplitFeatures.WARNED_FEATURE_DESTINATIONS.clear();
    CapturingServiceProvider.reset();
  }

  @Test
  void servesTheVariantWhenAConditionHasNoAttribute() {
    final Flag flag =
        flag(
            asList(
                allocation("by-country", "a", condition(ONE_OF, "country", singletonList("CA"))),
                allocation(
                    "null-then-plan",
                    "b",
                    condition(IS_NULL, null, true),
                    condition(ONE_OF, "plan", singletonList("pro")))));
    final MutableContext context = new MutableContext("user-1");
    context.add("country", "US");
    context.add("plan", "pro");

    final ProviderEvaluation<Integer> result =
        evaluate(new FlagEvaluator(false, notExposed()), flag, context);

    assertNull(result.getErrorCode());
    assertEquals(2, result.getValue());
  }

  @Test
  void readsEachAttributeOnceAcrossAllocationsAndNothingAcrossEvaluations() {
    final List<Allocation> allocations = new ArrayList<>();
    final List<ConditionConfiguration> all = new ArrayList<>();
    for (int i = 0; i < ATTRIBUTES; i++) {
      allocations.add(
          allocation("miss-" + i, "a", condition(ONE_OF, "attr" + i, singletonList("nomatch"))));
      all.add(condition(ONE_OF, "attr" + i, singletonList("v" + i)));
    }
    allocations.add(allocation("present-probe", "a", condition(IS_NULL, "missing", false)));
    all.add(condition(IS_NULL, "missing", true));
    allocations.add(allocation("all", "b", all.toArray(new ConditionConfiguration[0])));
    final Flag flag = flag(allocations);
    final FlagEvaluator evaluator = new FlagEvaluator(false, notExposed());

    final CountingContext first = context("user-1");
    final ProviderEvaluation<Integer> served = evaluate(evaluator, flag, first);
    assertNull(served.getErrorCode());
    assertEquals(2, served.getValue());
    for (int i = 0; i < ATTRIBUTES; i++) {
      assertEquals(1, first.reads("attr" + i), "attr" + i);
    }
    assertEquals(1, first.reads("missing"));

    final CountingContext second = context("user-2");
    second.add("missing", "present");
    final ProviderEvaluation<Integer> probed = evaluate(evaluator, flag, second);
    assertEquals(1, probed.getValue());
    assertEquals(1, second.reads("missing"));
  }

  @Test
  void warnsAboutAnUnknownDestinationOnceOnFirstServeAndNeverAtParse() throws IOException {
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.WARN);
    final ServerConfiguration config =
        UniversalFlagConfigParser.parse(configuration().getBytes(UTF_8));
    assertEquals(emptyList(), warnings());
    assertTrue(SplitFeatures.WARNED_FEATURE_DESTINATIONS.isEmpty());

    final FlagEvaluator evaluator = new FlagEvaluator(false, notExposed());
    for (int i = 0; i < 3; i++) {
      assertEquals(
          "on",
          evaluator
              .evaluate(config, String.class, "served", "default", new MutableContext("user-" + i))
              .getValue());
    }
    assertEquals(
        "on",
        evaluator
            .evaluate(config, String.class, "served-too", "default", new MutableContext("user-1"))
            .getValue());
    assertEquals(singletonList(WARNING + " FUTURE_SERVED"), warnings());
    assertEquals(singleton("FUTURE_SERVED"), SplitFeatures.WARNED_FEATURE_DESTINATIONS);

    SplitFeatures.WARNED_FEATURE_DESTINATIONS.clear();
    CapturingServiceProvider.events().clear();
    evaluator.evaluate(config, String.class, "served", "default", new MutableContext("user-9"));
    assertEquals(emptyList(), warnings());
  }

  private static List<String> warnings() {
    return CapturingServiceProvider.events().stream()
        .filter(e -> e.level == Level.WARN && e.message.startsWith(WARNING))
        .map(e -> e.message)
        .collect(toList());
  }

  private static String configuration() {
    return "{\"createdAt\":\"2024-04-17T19:40:53.716Z\",\"format\":\"SERVER\","
        + "\"environment\":{\"name\":\"Test\"},\"flags\":{"
        + flagJson("served", "FUTURE_SERVED")
        + ","
        + flagJson("served-too", "FUTURE_SERVED")
        + ","
        + flagJson("never", "FUTURE_NEVER")
        + "}}";
  }

  private static String flagJson(final String key, final String destination) {
    return "\""
        + key
        + "\":{\"key\":\""
        + key
        + "\",\"enabled\":true,\"variationType\":\"STRING\","
        + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":\"on\"}},"
        + "\"allocations\":[{\"key\":\"allocation\",\"rules\":[],\"doLog\":true,\"splits\":[{"
        + "\"variationKey\":\"on\",\"serialId\":7,\"shards\":[],"
        + "\"features\":[{\"key\":\"holdout.key\",\"value\":\"q4\",\"destinations\":[\"HOOK\",\""
        + destination
        + "\"]}]}]}]}";
  }

  private static ConditionConfiguration condition(
      final ConditionOperator operator, final String attribute, final Object value) {
    return new ConditionConfiguration(operator, attribute, value);
  }

  private static Allocation allocation(
      final String key, final String variant, final ConditionConfiguration... conditions) {
    return new Allocation(
        key,
        singletonList(new Rule(asList(conditions))),
        null,
        null,
        singletonList(new Split(emptyList(), variant, emptyMap(), null)),
        false);
  }

  private static Flag flag(final List<Allocation> allocations) {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("a", new Variant("a", 1));
    variations.put("b", new Variant("b", 2));
    return new Flag("flag", true, ValueType.INTEGER, variations, allocations);
  }

  private static ProviderEvaluation<Integer> evaluate(
      final FlagEvaluator evaluator, final Flag flag, final MutableContext context) {
    final Map<String, Flag> flags = new HashMap<>();
    flags.put("flag", flag);
    return evaluator.evaluate(
        new ServerConfiguration("", "", false, null, flags), Integer.class, "flag", 0, context);
  }

  private static FlagEvaluator.ExposureLookup notExposed() {
    return (flag, subject, allocation, variant, serialId) -> false;
  }

  private static CountingContext context(final String targetingKey) {
    final CountingContext context = new CountingContext(targetingKey);
    for (int i = 0; i < ATTRIBUTES; i++) {
      context.add("attr" + i, "v" + i);
    }
    return context;
  }

  private static final class CountingContext extends MutableContext {
    private final Map<String, Integer> reads = new HashMap<>();

    CountingContext(final String targetingKey) {
      super(targetingKey);
    }

    @Override
    public Value getValue(final String key) {
      reads.merge(key, 1, Integer::sum);
      return super.getValue(key);
    }

    int reads(final String key) {
      return reads.getOrDefault(key, 0);
    }
  }
}
