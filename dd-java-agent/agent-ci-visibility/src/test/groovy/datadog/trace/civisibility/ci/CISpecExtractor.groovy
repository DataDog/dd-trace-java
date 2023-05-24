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
      return Arrays.asList(new CISpec(Collections.EMPTY_MAP, Collections.EMPTY_MAP))
    }

    List<List<Map<String, String>>> spec = JSON_SLURPER.parse(CISpecExtractor.getClassLoader().getResourceAsStream(String.format("ci/%s.json", ciProviderName)))

    def ciSpecs = new ArrayList<CISpec>()
    spec.each {
      ciSpecs.add(new CISpec(it.get(0), it.get(1)))
    }

    return ciSpecs
  }

  static class CISpec {

    private final Map<String, String> env
    private final Map<String, String> tags

    CISpec(env, tags) {
      this.env = env
      this.tags = tags
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
