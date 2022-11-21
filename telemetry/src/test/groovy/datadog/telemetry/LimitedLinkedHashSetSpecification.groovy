package datadog.telemetry

import spock.lang.Specification

class LimitedLinkedHashSetSpecification extends Specification {

  void 'old elements pushed out'() {

    when:
    Set<String> set = new LimitedLinkedHashSet(3)
    set.add('one')
    set.add('two')
    set.add('three')
    set.add('four')

    then:
    set.size() == 3
    set == ['two', 'three', 'four'] as Set<String>

    when:
    set.addAll(['five', 'six'])

    then:
    set.size() == 3
    set == ['four', 'five', 'six'] as Set<String>
  }
}
