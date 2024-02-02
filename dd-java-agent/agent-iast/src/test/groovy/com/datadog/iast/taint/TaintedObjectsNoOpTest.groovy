package com.datadog.iast.taint

import com.datadog.iast.model.Source
import spock.lang.Specification

class TaintedObjectsNoOpTest extends Specification {


  void 'test no op implementation'() {
    setup:
    final instance = TaintedObjects.NoOp.INSTANCE
    final toTaint = 'test'

    when:
    final tainted = instance.taint(toTaint, Ranges.forCharSequence(toTaint, new Source(0 as byte, 'test', 'test')))

    then:
    tainted == null
    instance.get(toTaint) == null
    instance.count() == 0
    instance.size() == 0
    !instance.iterator().hasNext()

    when:
    instance.clear()

    then:
    noExceptionThrown()
  }
}
