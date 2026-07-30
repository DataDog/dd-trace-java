package datadog.openfeature.internal.core;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.openfeature.internal.core.EvaluationResult.Error;
import datadog.openfeature.internal.core.EvaluationResult.Reason;
import datadog.openfeature.internal.core.FlagEvaluator.ValueKind;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.ConditionConfiguration;
import datadog.trace.api.featureflag.ufc.v1.ConditionOperator;
import datadog.trace.api.featureflag.ufc.v1.Environment;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.Rule;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Shard;
import datadog.trace.api.featureflag.ufc.v1.ShardRange;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
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
        map("nested", true),
        evaluate(staticFlag(ValueType.JSON, map("nested", true)), ValueKind.OBJECT, context("id"))
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
            new Flag("flag", true, ValueType.STRING, map("on", new Variant("on", "value")), null)),
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
                map("on", new Variant("on", "value")),
                list(split("missing", emptyList())))),
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
            "flag", false, ValueType.STRING, map("on", new Variant("on", "value")), emptyList());
    assertEquals(Reason.DISABLED, evaluate(disabled, ValueKind.STRING, context("id")).reason);

    final Flag noSplits =
        flag(ValueType.STRING, map("on", new Variant("on", "value")), emptyList());
    assertEquals(Reason.DEFAULT, evaluate(noSplits, ValueKind.STRING, context("id")).reason);

    final long now = System.currentTimeMillis();
    final Flag inactive =
        new Flag(
            "flag",
            true,
            ValueType.STRING,
            map("on", new Variant("on", "value")),
            list(
                new Allocation(
                    "future",
                    emptyList(),
                    new Date(now + 60_000),
                    null,
                    list(split("on", emptyList())),
                    false),
                new Allocation(
                    "past",
                    emptyList(),
                    null,
                    new Date(now - 60_000),
                    list(split("on", emptyList())),
                    false)));
    assertEquals(Reason.DEFAULT, evaluate(inactive, ValueKind.STRING, context("id")).reason);
  }

  @Test
  void evaluatesAllRuleOperators() {
    final Rule matchingRule =
        new Rule(
            list(
                condition(ConditionOperator.MATCHES, "country", "^U"),
                condition(ConditionOperator.NOT_MATCHES, "country", "^C"),
                condition(ConditionOperator.ONE_OF, "tier", list("free", "paid")),
                condition(ConditionOperator.NOT_ONE_OF, "tier", list("blocked")),
                condition(ConditionOperator.GTE, "age", 18),
                condition(ConditionOperator.GT, "age", 17),
                condition(ConditionOperator.LTE, "age", 18),
                condition(ConditionOperator.LT, "age", 19),
                condition(ConditionOperator.IS_NULL, "missing", true),
                condition(ConditionOperator.IS_NULL, "country", false),
                condition(ConditionOperator.ONE_OF, "score", list("18"))));
    final Flag flag =
        new Flag(
            "flag",
            true,
            ValueType.STRING,
            map("on", new Variant("on", "matched")),
            list(
                new Allocation(
                    "targeted",
                    list(new Rule(emptyList()), matchingRule),
                    null,
                    null,
                    list(split("on", emptyList())),
                    false)));
    final EvaluationContext matching =
        new EvaluationContext(
            "subject", map("country", "US", "tier", "paid", "age", 18, "score", 18));

    assertEquals(Reason.TARGETING_MATCH, evaluate(flag, ValueKind.STRING, matching).reason);
    assertEquals(
        Reason.DEFAULT,
        evaluate(
                flag,
                ValueKind.STRING,
                new EvaluationContext(
                    "subject", map("country", "CA", "tier", "paid", "age", 18, "score", 18)))
            .reason);
  }

  @Test
  void mapsInvalidRulesToEvaluationErrors() {
    final Flag invalidRegex = targetedFlag(condition(ConditionOperator.MATCHES, "value", "["));
    assertEquals(
        Error.PARSE_ERROR,
        evaluate(invalidRegex, ValueKind.STRING, new EvaluationContext("id", map("value", "text")))
            .error);

    final Flag invalidNumber = targetedFlag(condition(ConditionOperator.GT, "value", "number"));
    assertEquals(
        Error.TYPE_MISMATCH,
        evaluate(invalidNumber, ValueKind.STRING, new EvaluationContext("id", map("value", 2)))
            .error);
  }

  @Test
  void evaluatesShardsAndRequiresTargetingKey() {
    final Shard matchingShard = new Shard("salt", list(new ShardRange(0, 1)), 1);
    final Flag sharded =
        flag(
            ValueType.STRING,
            map("on", new Variant("on", "value")),
            list(split("on", list(matchingShard))));

    assertEquals(Reason.SPLIT, evaluate(sharded, ValueKind.STRING, context("id")).reason);
    assertEquals(
        Error.TARGETING_KEY_MISSING, evaluate(sharded, ValueKind.STRING, context(null)).error);

    final Shard invalidShard = new Shard("salt", emptyList(), 0);
    final Flag unmatched =
        flag(
            ValueType.STRING,
            map("on", new Variant("on", "value")),
            list(split("on", list(invalidShard))));
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
        "explicit", new EvaluationContext("subject", map("id", "explicit")).attribute("id"));
    assertNull(new EvaluationContext("subject", null).attribute("missing"));
  }

  private EvaluationResult evaluate(
      final Flag flag, final ValueKind kind, final EvaluationContext context) {
    return evaluator.evaluate(snapshot(flag), kind, "flag", "default", context);
  }

  private void assertError(
      final ServerConfiguration snapshot,
      final ValueKind kind,
      final EvaluationContext context,
      final Error error) {
    assertEquals(error, evaluator.evaluate(snapshot, kind, "flag", "default", context).error);
  }

  private static ServerConfiguration snapshot(final Flag flag) {
    return new ServerConfiguration(null, "SERVER", new Environment("test"), map("flag", flag));
  }

  private static EvaluationContext context(final String targetingKey) {
    return new EvaluationContext(targetingKey, emptyMap());
  }

  private static Flag staticFlag(final ValueType type, final Object value) {
    return flag(type, map("on", new Variant("on", value)), list(split("on", emptyList())));
  }

  private static Flag targetedFlag(final ConditionConfiguration condition) {
    return new Flag(
        "flag",
        true,
        ValueType.STRING,
        map("on", new Variant("on", "value")),
        list(
            new Allocation(
                "allocation",
                list(new Rule(list(condition))),
                null,
                null,
                list(split("on", emptyList())),
                false)));
  }

  private static Flag flag(
      final ValueType type, final Map<String, Variant> variants, final List<Split> splits) {
    return new Flag(
        "flag",
        true,
        type,
        variants,
        list(new Allocation("allocation", emptyList(), null, null, splits, true)));
  }

  private static Split split(final String variationKey, final List<Shard> shards) {
    return new Split(shards, variationKey, emptyMap(), 7);
  }

  private static ConditionConfiguration condition(
      final ConditionOperator operator, final String attribute, final Object value) {
    return new ConditionConfiguration(operator, attribute, value);
  }

  @SafeVarargs
  private static <T> List<T> list(final T... values) {
    final List<T> result = new ArrayList<>(values.length);
    for (final T value : values) {
      result.add(value);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static <K, V> Map<K, V> map(final Object... keyValues) {
    final Map<K, V> result = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      result.put((K) keyValues[index], (V) keyValues[index + 1]);
    }
    return result;
  }
}
