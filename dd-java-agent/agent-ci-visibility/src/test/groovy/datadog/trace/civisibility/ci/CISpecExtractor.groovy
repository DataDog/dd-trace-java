package datadog.trace.civisibility.ci

import datadog.trace.api.DDTags
import groovy.json.JsonSlurper

class CISpecExtractor {

  private static final Set<String> JSON_TAGS = new HashSet<>(Arrays.asList(DDTags.CI_ENV_VARS))
  private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

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
          def expectedValue
          def actualValue
          if (JSON_TAGS.contains(val)) {
            actualValue = JSON_SLURPER.parseText(ciTags[val])
            expectedValue = JSON_SLURPER.parseText(tags[val])
          } else {
            actualValue = ciTags[val]
            expectedValue = tags[val]
          }

          if (actualValue != expectedValue) {
            mismatches.add("tag " + val + " is expected to have value \"" + expectedValue + "\", but has value \"" + actualValue + "\"")
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
