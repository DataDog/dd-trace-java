package datadog.trace.agent.tooling.log


import com.google.common.collect.testing.MapTestSuiteBuilder
import com.google.common.collect.testing.TestStringMapGenerator
import com.google.common.collect.testing.features.CollectionFeature
import com.google.common.collect.testing.features.CollectionSize
import com.google.common.collect.testing.features.MapFeature
import datadog.trace.test.util.DDSpecification
import junit.framework.TestResult

class UnionMapTest extends DDSpecification {

  def "test UnionMap behaviour"() {
    setup:
    def testResult = new TestResult()
    MapTestSuiteBuilder.using(new TestStringMapGenerator() {
        @Override
        protected Map<String, String> create(Map.Entry<String, String>[] entries) {
          Map<String, String> primary = new HashMap<>()
          Map<String, String> secondary = new HashMap<>()
          boolean addNextToPrimary = true
          for (Map.Entry<String, String> entry : entries) {
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
      .named("UnionMap")
      .withFeatures(
      MapFeature.GENERAL_PURPOSE,
      MapFeature.ALLOWS_NULL_KEYS,
      MapFeature.ALLOWS_NULL_VALUES,
      MapFeature.ALLOWS_ANY_NULL_QUERIES,
      MapFeature.FAILS_FAST_ON_CONCURRENT_MODIFICATION,
      CollectionFeature.SUPPORTS_ITERATOR_REMOVE,
      CollectionSize.ANY)
      .createTestSuite()
      .run(testResult)

    expect:
    testResult.wasSuccessful()
  }
}
