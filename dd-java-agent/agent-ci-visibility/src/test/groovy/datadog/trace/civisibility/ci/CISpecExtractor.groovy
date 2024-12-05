package datadog.trace.civisibility.ci

import datadog.trace.api.DDTags
import datadog.trace.bootstrap.instrumentation.api.Tags
import groovy.json.JsonSlurper

import java.util.function.BiPredicate

class CISpecExtractor {

  private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

  private static final Map<String, BiPredicate<String, String>> CUSTOM_COMPARATORS = new HashMap<>()

  static {
    CUSTOM_COMPARATORS.put(DDTags.CI_ENV_VARS, new BiPredicate<String, String>() {
        @Override
        boolean test(String e, String a) {
          return JSON_SLURPER.parseText(e) == JSON_SLURPER.parseText(a)
        }
      })
    CUSTOM_COMPARATORS.put(Tags.CI_NODE_LABELS, new BiPredicate<String, String>() {
        @Override
        boolean test(String e, String a) {
          return JSON_SLURPER.parseText(e).sort() == JSON_SLURPER.parseText(a).sort()
        }
      })
  }

  static extract(String ciProviderName) {
    if ("unknown" == ciProviderName) {
      return Arrays.asList(new CISpec(ciProviderName, 0, Collections.EMPTY_MAP, Collections.EMPTY_MAP))
    }

    List<List<Map<String, String>>> specs = JSON_SLURPER.parse(CISpecExtractor.getClassLoader().getResourceAsStream(String.format("ci/%s.json", ciProviderName)))

    def ciSpecs = new ArrayList<CISpec>()
    for (i in 0..<specs.size()) {
      def spec = specs.get(i)
      ciSpecs.add(new CISpec(ciProviderName, i, spec.get(0), spec.get(1)))
    }

    return ciSpecs
  }

  static class CISpec {

    private final String providerName
    private final int idx
    private final String testCaseName
    private final Map<String, String> env
    private final Map<String, String> tags

    CISpec(providerName, idx, env, tags) {
      this.providerName = providerName
      this.idx = idx
      if (env['DD_TEST_CASE_NAME']) {
        this.testCaseName =  ' - ' + env['DD_TEST_CASE_NAME']
      } else {
        this.testCaseName = ''
      }
      this.env = env
      this.tags = tags
    }

    String getProviderName() {
      return providerName
    }

    int getIdx() {
      return idx
    }

    String getTestCaseName() {
      return testCaseName
    }

    Collection<String> getTagMismatches(Map<String, String> ciTags) {
      Collection<String> mismatches = new ArrayList<>()

      def expectedKeysIterator = tags.keySet().iterator()
      while (expectedKeysIterator.hasNext()) {
        String val = expectedKeysIterator.next()
        if ((tags[val] && !ciTags[val]) || (!tags[val] && ciTags[val])) {
          mismatches.add("tag " + val + " is expected to have value \"" + tags[val] + "\", but has value \"" + ciTags[val] + "\"")
        } else {
          def expectedValue = tags[val]
          def actualValue = ciTags[val]

          boolean success

          def customComparator = CUSTOM_COMPARATORS.get(val)
          if (customComparator != null) {
            success = customComparator.test(expectedValue, actualValue)
          } else {
            success = actualValue == expectedValue
          }

          if (!success) {
            mismatches.add("tag " + val + " comparison failed. Expected:  \"" + expectedValue + "\", actual: \"" + actualValue + "\"")
          }
        }
        expectedKeysIterator.remove()
        ciTags.remove(val)
      }
      if (!ciTags.isEmpty()) {
        mismatches.add("unexpected tags: " + ciTags)
      }

      return mismatches
    }
  }
}
