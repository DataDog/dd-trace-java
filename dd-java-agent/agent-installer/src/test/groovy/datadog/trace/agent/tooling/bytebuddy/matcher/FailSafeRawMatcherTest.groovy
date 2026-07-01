package datadog.trace.agent.tooling.bytebuddy.matcher

import datadog.trace.test.util.DDSpecification
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.matcher.ElementMatcher

class FailSafeRawMatcherTest extends DDSpecification {

  def mockTypeMatcher = Mock(ElementMatcher)
  def mockLoaderMatcher = Mock(ElementMatcher)

  def "test matcher"() {
    setup:
    def matcher = new FailSafeRawMatcher(mockTypeMatcher, mockLoaderMatcher, "test")

    when:
    def result = matcher.matches(TypeDescription.ForLoadedType.of(Object), null, null, null, null)

    then:
    1 * mockLoaderMatcher.matches(_) >> loaderMatch
    if (loaderMatch) {
      1 * mockTypeMatcher.matches(_) >> typeMatch
    } else {
      0 * _
    }
    result == match

    where:
    loaderMatch | typeMatch | match
    true        | true      | true
    true        | false     | false
    false       | true      | false
    false       | false     | false
  }

  def "test loader matcher exception"() {
    setup:
    def matcher = new FailSafeRawMatcher(mockTypeMatcher, mockLoaderMatcher, "test")

    when:
    def result = matcher.matches(TypeDescription.ForLoadedType.of(Object), null, null, null, null)

    then:
    1 * mockLoaderMatcher.matches(_) >> { throw new Exception("matcher exception") }
    0 * _
    noExceptionThrown()
    !result // default to false
  }

  def "test type matcher exception"() {
    setup:
    def matcher = new FailSafeRawMatcher(mockTypeMatcher, mockLoaderMatcher, "test")

    when:
    def result = matcher.matches(TypeDescription.ForLoadedType.of(Object), null, null, null, null)

    then:
    1 * mockLoaderMatcher.matches(_) >> true
    1 * mockTypeMatcher.matches(_) >> { throw new Exception("matcher exception") }
    0 * _
    noExceptionThrown()
    !result // default to false
  }
}
