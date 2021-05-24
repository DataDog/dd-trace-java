package datadog.trace.api.http

import datadog.trace.test.util.DDSpecification

class AntPatternPathNormalizerTest extends DDSpecification {
  def "verify javadoc examples work"() {
    given:
    def matchers = [
      "/com/t?st.jsp": "1",
      "/com/*.jsp": "2",
      "/com/**/test.jsp": "3",
      "/com/datadoghq/dd-trace-java/**/*.jsp": "4",
      "/com/**/servlet/bla.jsp": "5"
    ]
    removeMapIndexes.toSorted().reverse().each { index ->
      matchers.remove(matchers.keySet().toArray()[index])
    }
    AntPatternPathNormalizer pathNormalizer = new AntPatternPathNormalizer(matchers, new FailingPathNormalizer())

    when:
    String result = pathNormalizer.normalize(path)

    then:
    result == normalizedPath

    where:
    path | normalizedPath | removeMapIndexes
    "/com/test.jsp" | "1" | []
    "/com/tast.jsp" | "1" | []
    "/com/txst.jsp" | "1" | []
    "/com/test.jsp" | "2" | [0]
    "/com/other.jsp" | "2" | []
    "/com/test.jsp" | "3" | [0, 1]
    "/com/1/test.jsp" | "3" | []
    "/com/1/a/test.jsp" | "3" | []
    "/com/2/test.jsp" | "3" | []
    "/com/datadoghq/dd-trace-java/index.jsp" | "4" | []
    "/com/datadoghq/dd-trace-java/1/index.jsp" | "4" | []
    "/com/datadoghq/dd-trace-java/1/a/index.jsp" | "4" | []
    "/com/datadoghq/dd-trace-java/2/index.jsp" | "4" | []
    "/com/servlet/bla.jsp" | "5" | []
    "/com/1/servlet/bla.jsp" | "5" | []
    "/com/1/a/servlet/bla.jsp" | "5" | []
    "/com/2/servlet/bla.jsp" | "5" | []
    "/fail" | null | []
  }

  def "verify counter-examples don't match"() {
    given:
    def matchers = [
      "/com/t?st.jsp": "1",
      "/com/*.jsp": "2",
      "/com/**/test.jsp": "3",
      "/com/datadoghq/dd-trace-java/**/*.jsp": "4",
      "/com/**/servlet/bla.jsp": "5",
    ]
    AntPatternPathNormalizer pathNormalizer = new AntPatternPathNormalizer(matchers, new FailingPathNormalizer())

    when:
    String result = pathNormalizer.normalize(path)

    then:
    result == null

    where:
    path << [null, "com/test.jsp", "/com/test.jspx", "/com/datadoghq"]
  }

  def "ignore patterns that aren't patterns"() {
    given:
    def matchers = [
      "/not/a/pattern": "1"
    ]
    AntPatternPathNormalizer normalizer = new AntPatternPathNormalizer(matchers, new FailingPathNormalizer())

    when:
    String result = normalizer.normalize("/not/a/pattern")

    then:
    result == null
  }

  static class FailingPathNormalizer extends PathNormalizer {
    @Override
    String normalize(String path, boolean encoded) {
      return null
    }
  }
}
