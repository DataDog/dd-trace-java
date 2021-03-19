package datadog.trace.api

import datadog.trace.test.util.DDSpecification

class PairTest extends DDSpecification {

  def "pump up the coverage"() {
    when:
    Pair<String, Integer> one = Pair.of("one", 1)
    Pair<String, Integer> two = Pair.of("two", 2)
    Pair<String, Integer> nothing = Pair.of(null, null)
    then:
    one == one
    one == Pair.of("one", 1)
    one != two
    one != nothing
    nothing != one
    nothing == nothing
    one.getLeft() != two.getLeft()
    one.getRight() != two.getRight()
    one.hashCode() != two.hashCode()
    one.hashCode() != nothing.hashCode()
    one.hasLeft()
    one.hasRight()
    !nothing.hasLeft()
    !nothing.hasRight()
  }
}
