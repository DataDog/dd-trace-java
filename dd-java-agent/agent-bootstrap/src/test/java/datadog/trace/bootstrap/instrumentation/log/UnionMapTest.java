package datadog.trace.bootstrap.instrumentation.log;

import static com.google.common.collect.testing.features.CollectionFeature.SUPPORTS_ITERATOR_REMOVE;
import static com.google.common.collect.testing.features.MapFeature.ALLOWS_ANY_NULL_QUERIES;
import static com.google.common.collect.testing.features.MapFeature.ALLOWS_NULL_KEYS;
import static com.google.common.collect.testing.features.MapFeature.ALLOWS_NULL_VALUES;
import static com.google.common.collect.testing.features.MapFeature.GENERAL_PURPOSE;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.testing.MapTestSuiteBuilder;
import com.google.common.collect.testing.TestStringMapGenerator;
import com.google.common.collect.testing.features.CollectionSize;
import java.util.HashMap;
import java.util.Map;
import junit.framework.TestResult;
import org.junit.jupiter.api.Test;

class UnionMapTest {

  /** Builds the map under test from the entries provided by the Guava test suite. */
  private interface MapFactory {
    Map<String, String> create(Map.Entry<String, String>[] entries);
  }

  @Test
  void testMapBehaviourWhenPrimaryIsEmpty() {
    assertMapTestsPass(
        "UnionMap - secondary only",
        entries -> {
          Map<String, String> secondary = new HashMap<>();
          for (Map.Entry<String, String> entry : entries) {
            secondary.put(entry.getKey(), entry.getValue());
          }
          return new UnionMap<>(new HashMap<>(), secondary);
        });
  }

  @Test
  void testMapBehaviourWhenSecondaryIsEmpty() {
    assertMapTestsPass(
        "UnionMap - primary only",
        entries -> {
          Map<String, String> primary = new HashMap<>();
          for (Map.Entry<String, String> entry : entries) {
            primary.put(entry.getKey(), entry.getValue());
          }
          return new UnionMap<>(primary, new HashMap<>());
        });
  }

  @Test
  void testMapBehaviourWhenPrimaryIsSameAsSecondary() {
    assertMapTestsPass(
        "UnionMap - duplicates",
        entries -> {
          Map<String, String> primary = new HashMap<>();
          Map<String, String> secondary = new HashMap<>();
          for (Map.Entry<String, String> entry : entries) {
            primary.put(entry.getKey(), entry.getValue());
            secondary.put(entry.getKey(), entry.getValue());
          }
          return new UnionMap<>(primary, secondary);
        });
  }

  @Test
  void testMapBehaviourWhenSecondaryIsAllNulls() {
    assertMapTestsPass(
        "UnionMap - primary only",
        entries -> {
          Map<String, String> primary = new HashMap<>();
          Map<String, String> secondary = new HashMap<>();
          for (Map.Entry<String, String> entry : entries) {
            primary.put(entry.getKey(), entry.getValue());
            secondary.put(entry.getKey(), null);
          }
          return new UnionMap<>(primary, secondary);
        });
  }

  @Test
  void testMapBehaviourWhenSecondaryHasDifferentValues() {
    assertMapTestsPass(
        "UnionMap - primary only",
        entries -> {
          Map<String, String> primary = new HashMap<>();
          Map<String, String> secondary = new HashMap<>();
          for (Map.Entry<String, String> entry : entries) {
            primary.put(entry.getKey(), entry.getValue());
            secondary.put(entry.getKey(), "");
          }
          return new UnionMap<>(primary, secondary);
        });
  }

  @Test
  void testMapBehaviourWhenEntriesAreMixedBetweenPrimaryAndSecondary() {
    assertMapTestsPass(
        "UnionMap - mixture",
        entries -> {
          Map<String, String> primary = new HashMap<>();
          Map<String, String> secondary = new HashMap<>();
          boolean addNextToPrimary = false;
          for (Map.Entry<String, String> entry : entries) {
            // if the key is already in primary then put the duplicate there
            // because adding it to the secondary won't override the primary
            if (addNextToPrimary || primary.containsKey(entry.getKey())) {
              primary.put(entry.getKey(), entry.getValue());
              addNextToPrimary = false;
            } else {
              secondary.put(entry.getKey(), entry.getValue());
              addNextToPrimary = true;
            }
          }
          return new UnionMap<>(primary, secondary);
        });
  }

  private static void assertMapTestsPass(String name, MapFactory mapFactory) {
    TestResult testResult = new TestResult();

    MapTestSuiteBuilder.using(
            new TestStringMapGenerator() {
              @Override
              protected Map<String, String> create(Map.Entry<String, String>[] entries) {
                return mapFactory.create(entries);
              }
            })
        .named(name)
        .withFeatures(
            GENERAL_PURPOSE,
            ALLOWS_NULL_KEYS,
            ALLOWS_NULL_VALUES,
            ALLOWS_ANY_NULL_QUERIES,
            SUPPORTS_ITERATOR_REMOVE,
            CollectionSize.ANY)
        .createTestSuite()
        .run(testResult);

    assertTrue(
        testResult.wasSuccessful(),
        () ->
            name
                + ": "
                + testResult.failureCount()
                + " failure(s), "
                + testResult.errorCount()
                + " error(s)");
  }
}
