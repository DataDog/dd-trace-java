package datadog.openfeature.internal.core;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.openfeature.internal.core.ConfigurationSnapshot.Allocation;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Condition;
import datadog.openfeature.internal.core.ConfigurationSnapshot.ConditionOperator;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Flag;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Rule;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Shard;
import datadog.openfeature.internal.core.ConfigurationSnapshot.ShardRange;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Split;
import datadog.openfeature.internal.core.ConfigurationSnapshot.ValueType;
import datadog.openfeature.internal.core.ConfigurationSnapshot.Variant;
import datadog.openfeature.internal.core.EvaluationResult.Error;
import datadog.openfeature.internal.core.EvaluationResult.Reason;
import datadog.openfeature.internal.core.FlagEvaluator.ValueKind;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlagEvaluatorTest {

  private final FlagEvaluator evaluator = new FlagEvaluator();

  @Test
  void evaluatesStaticValuesAndMetadata() {
    final EvaluationResult result =
        evaluate(staticFlag(ValueType.STRING, "hello"), ValueKind.STRING, context("subject"));

    assertEquals("hello", result.value);
    assertEquals("on", result.variant);
    assertEquals(Reason.STATIC, result.reason);
    assertEquals("allocation", result.allocationKey);
    assertEquals(7, result.splitSerialId);
    assertEquals(true, result.doLog);
  }

  @Test
  void mapsAllSupportedValueKinds() {
    assertEquals(
        true, evaluate(staticFlag(ValueType.BOOLEAN, 1), ValueKind.BOOLEAN, context("id")).value);
    assertEquals(
        42,
        evaluate(staticFlag(ValueType.INTEGER, "42.9"), ValueKind.INTEGER, context("id")).value);
    assertEquals(
        42D, evaluate(staticFlag(ValueType.NUMERIC, "42"), ValueKind.DOUBLE, context("id")).value);
    assertEquals(
        "42", evaluate(staticFlag(ValueType.STRING, 42), ValueKind.STRING, context("id")).value);
    assertEquals(
        Map.of("nested", true),
        evaluate(
                staticFlag(ValueType.JSON, Map.of("nested", true)), ValueKind.OBJECT, context("id"))
            .value);
    assertNull(FlagEvaluator.mapValue(ValueKind.STRING, null));
  }

  @Test
  void reportsInputAndConfigurationErrors() {
    assertError(null, ValueKind.STRING, context("id"), Error.PROVIDER_NOT_READY);
    assertError(
        snapshot(staticFlag(ValueType.STRING, "value")),
        ValueKind.STRING,
        null,
        Error.INVALID_CONTEXT);

    final EvaluationResult missing =
        evaluator.evaluate(
            snapshot(staticFlag(ValueType.STRING, "value")),
            ValueKind.STRING,
            "missing",
            "default",
            context("id"));
    assertEquals(Error.FLAG_NOT_FOUND, missing.error);

    assertError(
        snapshot(
            new Flag(
                "flag", true, ValueType.STRING, Map.of("on", new Variant("on", "value")), null)),
        ValueKind.STRING,
        context("id"),
        Error.GENERAL);
    assertError(
        snapshot(staticFlag(ValueType.STRING, "value")),
        ValueKind.BOOLEAN,
        context("id"),
        Error.TYPE_MISMATCH);
    assertError(
        snapshot(
            flag(
                ValueType.STRING,
                Map.of("on", new Variant("on", "value")),
                List.of(split("missing", emptyList())))),
        ValueKind.STRING,
        context("id"),
        Error.GENERAL);
    assertError(
        snapshot(staticFlag(ValueType.INTEGER, "not-a-number")),
        ValueKind.INTEGER,
        context("id"),
        Error.PARSE_ERROR);
  }

  @Test
  void returnsDisabledAndDefaultResults() {
    final Flag disabled =
        new Flag(
            "flag", false, ValueType.STRING, Map.of("on", new Variant("on", "value")), emptyList());
    assertEquals(Reason.DISABLED, evaluate(disabled, ValueKind.STRING, context("id")).reason);

    final Flag noSplits =
        flag(ValueType.STRING, Map.of("on", new Variant("on", "value")), emptyList());
    assertEquals(Reason.DEFAULT, evaluate(noSplits, ValueKind.STRING, context("id")).reason);

    final long now = System.currentTimeMillis();
    final Flag inactive =
        new Flag(
            "flag",
            true,
            ValueType.STRING,
            Map.of("on", new Variant("on", "value")),
            List.of(
                new Allocation(
                    "future",
                    emptyList(),
                    now + 60_000,
                    null,
                    List.of(split("on", emptyList())),
                    false),
                new Allocation(
                    "past",
                    emptyList(),
                    null,
                    now - 60_000,
                    List.of(split("on", emptyList())),
                    false)));
    assertEquals(Reason.DEFAULT, evaluate(inactive, ValueKind.STRING, context("id")).reason);
  }

  @Test
  void evaluatesAllRuleOperators() {
    final Rule matchingRule =
        new Rule(
            List.of(
                condition(ConditionOperator.MATCHES, "country", "^U"),
                condition(ConditionOperator.NOT_MATCHES, "country", "^C"),
                condition(ConditionOperator.ONE_OF, "tier", List.of("free", "paid")),
                condition(ConditionOperator.NOT_ONE_OF, "tier", List.of("blocked")),
                condition(ConditionOperator.GTE, "age", 18),
                condition(ConditionOperator.GT, "age", 17),
                condition(ConditionOperator.LTE, "age", 18),
                condition(ConditionOperator.LT, "age", 19),
                condition(ConditionOperator.IS_NULL, "missing", true),
                condition(ConditionOperator.IS_NULL, "country", false),
                condition(ConditionOperator.ONE_OF, "score", List.of("18"))));
    final Flag flag =
        new Flag(
            "flag",
            true,
            ValueType.STRING,
            Map.of("on", new Variant("on", "matched")),
            List.of(
                new Allocation(
                    "targeted",
                    List.of(new Rule(emptyList()), matchingRule),
                    null,
                    null,
                    List.of(split("on", emptyList())),
                    false)));
    final EvaluationContext matching =
        new EvaluationContext(
            "subject", Map.of("country", "US", "tier", "paid", "age", 18, "score", 18));

    assertEquals(Reason.TARGETING_MATCH, evaluate(flag, ValueKind.STRING, matching).reason);
    assertEquals(
        Reason.DEFAULT,
        evaluate(
                flag,
                ValueKind.STRING,
                new EvaluationContext(
                    "subject", Map.of("country", "CA", "tier", "paid", "age", 18, "score", 18)))
            .reason);
  }

  @Test
  void mapsInvalidRulesToEvaluationErrors() {
    final Flag invalidRegex = targetedFlag(condition(ConditionOperator.MATCHES, "value", "["));
    assertEquals(
        Error.PARSE_ERROR,
        evaluate(
                invalidRegex,
                ValueKind.STRING,
                new EvaluationContext("id", Map.of("value", "text")))
            .error);

    final Flag invalidNumber = targetedFlag(condition(ConditionOperator.GT, "value", "number"));
    assertEquals(
        Error.TYPE_MISMATCH,
        evaluate(invalidNumber, ValueKind.STRING, new EvaluationContext("id", Map.of("value", 2)))
            .error);
  }

  @Test
  void evaluatesShardsAndRequiresTargetingKey() {
    final Shard matchingShard = new Shard("salt", List.of(new ShardRange(0, 1)), 1);
    final Flag sharded =
        flag(
            ValueType.STRING,
            Map.of("on", new Variant("on", "value")),
            List.of(split("on", List.of(matchingShard))));

    assertEquals(Reason.SPLIT, evaluate(sharded, ValueKind.STRING, context("id")).reason);
    assertEquals(
        Error.TARGETING_KEY_MISSING, evaluate(sharded, ValueKind.STRING, context(null)).error);

    final Shard invalidShard = new Shard("salt", emptyList(), 0);
    final Flag unmatched =
        flag(
            ValueType.STRING,
            Map.of("on", new Variant("on", "value")),
            List.of(split("on", List.of(invalidShard))));
    assertEquals(Reason.DEFAULT, evaluate(unmatched, ValueKind.STRING, context("id")).reason);
  }

  @Test
  void contextUsesTargetingKeyAsDefaultIdAndCopiesAttributes() {
    final java.util.Map<String, Object> mutable = new java.util.LinkedHashMap<>();
    mutable.put("name", "value");
    final EvaluationContext context = new EvaluationContext("subject", mutable);
    mutable.put("name", "changed");

    assertEquals("subject", context.attribute("id"));
    assertEquals("value", context.attribute("name"));
    assertEquals(
        "explicit", new EvaluationContext("subject", Map.of("id", "explicit")).attribute("id"));
    assertNull(new EvaluationContext("subject", null).attribute("missing"));
  }

  private EvaluationResult evaluate(
      final Flag flag, final ValueKind kind, final EvaluationContext context) {
    return evaluator.evaluate(snapshot(flag), kind, "flag", "default", context);
  }

  private void assertError(
      final ConfigurationSnapshot snapshot,
      final ValueKind kind,
      final EvaluationContext context,
      final Error error) {
    assertEquals(error, evaluator.evaluate(snapshot, kind, "flag", "default", context).error);
  }

  private static ConfigurationSnapshot snapshot(final Flag flag) {
    return new ConfigurationSnapshot(null, "SERVER", "test", Map.of("flag", flag));
  }

  private static EvaluationContext context(final String targetingKey) {
    return new EvaluationContext(targetingKey, emptyMap());
  }

  private static Flag staticFlag(final ValueType type, final Object value) {
    return flag(type, Map.of("on", new Variant("on", value)), List.of(split("on", emptyList())));
  }

  private static Flag targetedFlag(final Condition condition) {
    return new Flag(
        "flag",
        true,
        ValueType.STRING,
        Map.of("on", new Variant("on", "value")),
        List.of(
            new Allocation(
                "allocation",
                List.of(new Rule(List.of(condition))),
                null,
                null,
                List.of(split("on", emptyList())),
                false)));
  }

  private static Flag flag(
      final ValueType type, final Map<String, Variant> variants, final List<Split> splits) {
    return new Flag(
        "flag",
        true,
        type,
        variants,
        List.of(new Allocation("allocation", emptyList(), null, null, splits, true)));
  }

  private static Split split(final String variationKey, final List<Shard> shards) {
    return new Split(shards, variationKey, emptyMap(), 7);
  }

  private static Condition condition(
      final ConditionOperator operator, final String attribute, final Object value) {
    return new Condition(operator, attribute, value);
  }
}
