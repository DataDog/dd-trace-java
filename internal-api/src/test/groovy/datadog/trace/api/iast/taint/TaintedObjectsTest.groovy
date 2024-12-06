package datadog.trace.api.iast.taint

import spock.lang.Specification

class TaintedObjectsTest extends Specification {

  void 'test noop instance'() {
    setup:
    final to = TaintedObjects.NoOp.INSTANCE
    final toTaint = new Object()

    when:
    to.taint(toTaint, [] as Range[])

    then:
    to.count() == 0

    when:
    final result = to.isTainted(toTaint)

    then:
    !result

    when:
    final tainted = to.get(toTaint)

    then:
    tainted == null
  }
}
