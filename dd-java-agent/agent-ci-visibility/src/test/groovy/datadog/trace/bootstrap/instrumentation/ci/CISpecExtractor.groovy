package datadog.trace.bootstrap.instrumentation.ci

import datadog.trace.api.DDTags
import groovy.json.JsonSlurper

class CISpecExtractor {

  private static final List<String> JSON_TAGS = Arrays.asList(DDTags.CI_ENV_VARS)
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

    boolean assertTags(Map<String, String> ciTags) {
      for(String val : JSON_TAGS) {
        if(!tags[val] && !ciTags[val]) {
          continue
        } else if ((tags[val] && !ciTags[val]) || (!tags[val] && ciTags[val])) {
          return false
        } else {
          def jsonObj = JSON_SLURPER.parseText(ciTags[val])
          def expectedJsonObj = JSON_SLURPER.parseText(tags[val])
          if (jsonObj != expectedJsonObj) {
            return false
          }
        }
        tags.remove(val)
        ciTags.remove(val)
      }
      return tags == ciTags
    }
  }
}
