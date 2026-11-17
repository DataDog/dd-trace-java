package com.datadog.openfeature.internal.evaluation;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.ConditionConfiguration;
import com.datadog.openfeature.internal.ufc.ConditionOperator;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.MembershipIndex;
import com.datadog.openfeature.internal.ufc.Rule;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import dev.openfeature.sdk.Value;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

/**
 * The ONE_OF/NOT_ONE_OF index gives the same result as the scan of the list, error cases included.
 */
class FlagEvaluatorMembershipTest {
  private static final String FLAG = "f";

  /** Elements that exercise each comparison rule of the scan. */
  private static final Object[] ELEMENTS = {
    "42",
    "042",
    "42.0",
    " 42 ",
    "4.2e1",
    "-0",
    "0",
    "1e3",
    "NaN",
    "Infinity",
    "abc",
    "true",
    "null",
    "",
    "acct-1",
    42.0,
    0.0,
    -0.0,
    7.5,
    Boolean.TRUE,
    Boolean.FALSE,
    null,
    Arrays.asList("42"),
    Collections.singletonMap("k", 1.0)
  };

  /** Attributes of each type that the context can hold. */
  private static final Object[] ATTRIBUTES = {
    "42",
    "042",
    "42.0",
    "abc",
    "true",
    "false",
    "null",
    "",
    "-0",
    "1e3",
    "NaN",
    "Infinity",
    "acct-1",
    "[42]",
    "{k=1.0}",
    42,
    0,
    1000,
    7,
    42L,
    9_007_199_254_740_993L,
    42.0,
    -0.0,
    0.0,
    7.5,
    Double.NaN,
    Boolean.TRUE,
    Boolean.FALSE
  };

  private final FlagEvaluator evaluator = new FlagEvaluator(false, (f, s, a, v, id) -> false);

  @Test
  void indexGivesTheSameResultAsTheScan() {
    final Random random = new Random(20261116);
    for (int trial = 0; trial < 4_000; trial++) {
      final List<Object> list = new ArrayList<>();
      final int size = MembershipIndexSizes.MIN + random.nextInt(24);
      for (int i = 0; i < size; i++) {
        // Mostly numeric strings, as UFC lists are, with each special element now and then.
        list.add(
            random.nextInt(3) == 0
                ? ELEMENTS[random.nextInt(ELEMENTS.length)]
                : Integer.toString(random.nextInt(60)));
      }
      for (final ConditionOperator operator :
          Arrays.asList(ConditionOperator.ONE_OF, ConditionOperator.NOT_ONE_OF)) {
        for (final Object attribute : ATTRIBUTES) {
          assertSameResult(operator, list, attribute);
        }
        assertSameResult(operator, list, Integer.toString(random.nextInt(60)));
        assertSameResult(operator, list, random.nextInt(60));
      }
    }
  }

  @TableTest({
    "scenario                         | list               | attribute | indexed | scan ",
    "numeric string matches integer   | 1,2,3,4,5,6,7,042  | 42        | MATCH   | MATCH",
    "absent integer                   | 1,2,3,4,5,6,7,8    | 42        | NO      | NO   ",
    "unconvertible element after hit  | 42,2,3,4,5,6,7,abc | 42        | MATCH   | MATCH",
    "unconvertible element before hit | abc,2,3,4,5,6,7,42 | 42        | ERROR   | ERROR",
    "string list with integer         | a,b,c,d,e,f,g,h    | 42        | ERROR   | ERROR"
  })
  void matchesTheScanForIntegerAttributes(
      final String list, final int attribute, final String indexed, final String scan) {
    final List<Object> values = new ArrayList<>(Arrays.asList((Object[]) list.split(",")));
    assertEquals(scan, outcome(evaluate(ConditionOperator.ONE_OF, values, attribute, false)));
    assertEquals(indexed, outcome(evaluate(ConditionOperator.ONE_OF, values, attribute, true)));
  }

  @Test
  void indexesOnlyListsOfTheMinimumSize() {
    final List<Object> shortList = Collections.nCopies(MembershipIndexSizes.MIN - 1, "x");
    final List<Object> longList = Collections.nCopies(MembershipIndexSizes.MIN, "x");

    assertNull(MembershipIndex.build(shortList));
    assertNotNull(MembershipIndex.build(longList));
    assertNull(MembershipIndex.build("not a list"));
  }

  @Test
  void findsTheFirstAndLastEntriesOfALargeList() {
    final List<Object> accounts = new ArrayList<>();
    for (int i = 0; i < 10_000; i++) {
      accounts.add(String.format("acct-%05d", i));
    }

    assertEquals(
        "MATCH", outcome(evaluate(ConditionOperator.ONE_OF, accounts, "acct-00000", true)));
    assertEquals(
        "MATCH", outcome(evaluate(ConditionOperator.ONE_OF, accounts, "acct-09999", true)));
    assertEquals("NO", outcome(evaluate(ConditionOperator.ONE_OF, accounts, "acct-10000", true)));
    assertEquals("NO", outcome(evaluate(ConditionOperator.ONE_OF, accounts, "ACCT-00001", true)));
    assertEquals(
        "MATCH", outcome(evaluate(ConditionOperator.NOT_ONE_OF, accounts, "acct-10000", true)));
  }

  @Test
  void scansAttributeTypesTheIndexDoesNotHandle() {
    final Object[] attributes = {
      Instant.parse("2024-01-01T00:00:00Z"), Arrays.asList("42"), Collections.singletonMap("k", "v")
    };
    final List<List<Object>> lists =
        Arrays.asList(
            Arrays.asList("2024-01-01T00:00:00Z", "x"),
            Arrays.asList("[42]", "x"),
            Arrays.asList("{k=v}", "x"),
            Arrays.asList(42.0, "x"));
    for (final ConditionOperator operator :
        Arrays.asList(ConditionOperator.ONE_OF, ConditionOperator.NOT_ONE_OF)) {
      for (final List<Object> list : lists) {
        for (final Object attribute : attributes) {
          assertSameResult(operator, list, attribute);
        }
      }
    }
    assertEquals(
        "ERROR", outcome(evaluate(ConditionOperator.ONE_OF, lists.get(3), attributes[0], true)));
  }

  private void assertSameResult(
      final ConditionOperator operator, final List<Object> list, final Object attribute) {
    final ProviderEvaluation<String> scan = evaluate(operator, list, attribute, false);
    final ProviderEvaluation<String> indexed = evaluate(operator, list, attribute, true);
    final String where = operator + " " + list + " attribute " + describe(attribute);
    assertEquals(scan.getValue(), indexed.getValue(), where);
    assertEquals(scan.getReason(), indexed.getReason(), where);
    assertEquals(scan.getErrorCode(), indexed.getErrorCode(), where);
  }

  private ProviderEvaluation<String> evaluate(
      final ConditionOperator operator,
      final List<Object> list,
      final Object attribute,
      final boolean indexed) {
    final ConditionConfiguration condition = new ConditionConfiguration(operator, "attr", list);
    if (indexed) {
      condition.membership = MembershipIndex.build(list);
      assertNotNull(condition.membership);
    }
    final Allocation allocation =
        new Allocation(
            "a",
            singletonList(new Rule(singletonList(condition))),
            null,
            null,
            singletonList(new Split(emptyList(), "in", null, null)),
            false);
    final Flag flag =
        new Flag(
            FLAG,
            true,
            ValueType.STRING,
            Collections.singletonMap("in", new Variant("in", "in")),
            singletonList(allocation));
    final ServerConfiguration config =
        new ServerConfiguration("", "", null, null, Collections.singletonMap(FLAG, flag));
    final MutableContext context =
        new MutableContext("user", Collections.singletonMap("attr", toValue(attribute)));
    return evaluator.evaluate(config, String.class, FLAG, "out", context);
  }

  private static Value toValue(final Object attribute) {
    if (attribute instanceof Long) {
      return new Value((Long) attribute);
    }
    return Value.objectToValue(attribute);
  }

  private static String outcome(final ProviderEvaluation<String> evaluation) {
    if (evaluation.getErrorCode() != null) {
      return "ERROR";
    }
    return "in".equals(evaluation.getValue()) ? "MATCH" : "NO";
  }

  private static String describe(final Object attribute) {
    return attribute == null
        ? "null"
        : attribute + " (" + attribute.getClass().getSimpleName() + ")";
  }

  /** The minimum indexed size, read from the index so that the test follows it. */
  private static final class MembershipIndexSizes {
    static final int MIN = minIndexedSize();

    private static int minIndexedSize() {
      for (int size = 1; size < 64; size++) {
        if (MembershipIndex.build(Collections.nCopies(size, "x")) != null) {
          return size;
        }
      }
      throw new IllegalStateException("no list size is indexed");
    }
  }
}
