package datadog.trace.agent.tooling.log


import com.google.common.collect.testing.MapTestSuiteBuilder
import com.google.common.collect.testing.TestStringMapGenerator
import com.google.common.collect.testing.features.CollectionFeature
import com.google.common.collect.testing.features.CollectionSize
import com.google.common.collect.testing.features.MapFeature
import datadog.trace.test.util.DDSpecification
import junit.framework.TestResult

class UnionMapTest extends DDSpecification {

  def "test map behaviour when primary is empty"() {
    setup:
    def testResult = runMapTests("UnionMap - secondary only", new TestStringMapGenerator() {
        @Override
        protected Map<String, String> create(Map.Entry<String, String>[] entries) {
          Map<String, String> secondary = new HashMap<>()
          for (Map.Entry<String, String> entry : entries) {
            secondary.put(entry.key, entry.value)
          }
          return new UnionMap<>(new HashMap<>(), secondary)
        }
      })

    expect:
    testResult.wasSuccessful()
  }

  def "test map behaviour when secondary is empty"() {
    setup:
    def testResult = runMapTests("UnionMap - primary only", new TestStringMapGenerator() {
        @Override
        protected Map<String, String> create(Map.Entry<String, String>[] entries) {
          Map<String, String> primary = new HashMap<>()
          for (Map.Entry<String, String> entry : entries) {
            primary.put(entry.key, entry.value)
          }
          return new UnionMap<>(primary, new HashMap<>())
        }
      })

    expect:
    testResult.wasSuccessful()
  }

  def "test map behaviour when primary is same as secondary"() {
    setup:
    def testResult = runMapTests("UnionMap - duplicates", new TestStringMapGenerator() {
        @Override
        protected Map<String, String> create(Map.Entry<String, String>[] entries) {
          Map<String, String> primary = new HashMap<>()
          Map<String, String> secondary = new HashMap<>()
          for (Map.Entry<String, String> entry : entries) {
            primary.put(entry.key, entry.value)
            secondary.put(entry.key, entry.value)
          }
          return new UnionMap<>(primary, secondary)
        }
      })

    expect:
    testResult.wasSuccessful()
  }

  def "test map behaviour when secondary is all nulls"() {
    setup:
    def testResult = runMapTests("UnionMap - primary only", new TestStringMapGenerator() {
        @Override
        protected Map<String, String> create(Map.Entry<String, String>[] entries) {
          Map<String, String> primary = new HashMap<>()
          Map<String, String> secondary = new HashMap<>()
          for (Map.Entry<String, String> entry : entries) {
            primary.put(entry.key, entry.value)
            secondary.put(entry.key, null)
          }
          return new UnionMap<>(primary, secondary)
        }
      })

    expect:
    testResult.wasSuccessful()
  }

  def "test map behaviour when secondary has different values"() {
    setup:
    def testResult = runMapTests("UnionMap - primary only", new TestStringMapGenerator() {
        @Override
        protected Map<String, String> create(Map.Entry<String, String>[] entries) {
          Map<String, String> primary = new HashMap<>()
          Map<String, String> secondary = new HashMap<>()
          for (Map.Entry<String, String> entry : entries) {
            primary.put(entry.key, entry.value)
            secondary.put(entry.key, "")
          }
          return new UnionMap<>(primary, secondary)
        }
      })

    expect:
    testResult.wasSuccessful()
  }

  def "test map behaviour when entries are mixed between primary and secondary"() {
    setup:
    def testResult = runMapTests("UnionMap - mixture", new TestStringMapGenerator() {
        @Override
        protected Map<String, String> create(Map.Entry<String, String>[] entries) {
          Map<String, String> primary = new HashMap<>()
          Map<String, String> secondary = new HashMap<>()
          boolean addNextToPrimary = false
          for (Map.Entry<String, String> entry : entries) {
            // if the key is already in primary then put the duplicate there
            // because adding it to the secondary won't override the primary
            if (addNextToPrimary || primary.containsKey(entry.key)) {
              primary.put(entry.key, entry.value)
              addNextToPrimary = false
            } else {
              secondary.put(entry.key, entry.value)
              addNextToPrimary = true
            }
          }
          return new UnionMap<>(primary, secondary)
        }
      })

    expect:
    testResult.wasSuccessful()
  }


  def runMapTests(name, generator) {
    def testResult = new TestResult()

    MapTestSuiteBuilder
      .using(generator)
      .named(name)
      .withFeatures(
      MapFeature.GENERAL_PURPOSE,
      MapFeature.ALLOWS_NULL_KEYS,
      MapFeature.ALLOWS_NULL_VALUES,
      MapFeature.ALLOWS_ANY_NULL_QUERIES,
      CollectionFeature.SUPPORTS_ITERATOR_REMOVE,
      CollectionSize.ANY)
      .createTestSuite()
      .run(testResult)

    return testResult
  }
}
