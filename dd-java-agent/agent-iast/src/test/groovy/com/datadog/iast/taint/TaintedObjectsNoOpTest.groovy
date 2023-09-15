package com.datadog.iast.taint

import com.datadog.iast.model.Source
import spock.lang.Specification

class TaintedObjectsNoOpTest extends Specification {


  void 'test no op implementation'() {
    setup:
    final instance = TaintedObjects.NoOp.INSTANCE
    final toTaint = 'test'

    when:
    final taintedString = instance.taintInputString(toTaint, new Source(0 as byte, 'test', 'test'))
    final taintedObject = instance.taintInputObject(toTaint, new Source(0 as byte, 'test', 'test'))

    then:
    taintedString == null
    taintedObject == null
    instance.get(toTaint) == null
    instance.estimatedSize == 0
    instance.size() == 0
    !instance.flat
    !instance.iterator().hasNext()

    when:
    instance.release()

    then:
    noExceptionThrown()
  }
}
