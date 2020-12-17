package datadog.trace.bootstrap.instrumentation.api.ci

import groovy.json.JsonSlurper

class CISpecExtractor {

  static def extract(String ciProviderName) {
    if ("noop" == ciProviderName) {
      return Arrays.asList(new CISpec(Collections.EMPTY_MAP, Collections.EMPTY_MAP))
    }

    def jsonSlurper = new JsonSlurper()
    List<List<Map<String, String>>> spec = jsonSlurper.parse(CISpecExtractor.class.getClassLoader().getResourceAsStream(String.format("ci/%s.json", ciProviderName)))

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
  }
}
