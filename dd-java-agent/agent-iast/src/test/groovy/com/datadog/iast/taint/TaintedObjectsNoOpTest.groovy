package com.datadog.iast.taint

import com.datadog.iast.model.Source
import datadog.trace.api.iast.SourceTypes
import spock.lang.Specification

class TaintedObjectsNoOpTest extends Specification {


  void 'test no op tainted objects'() {
    setup:
    final to = TaintedObjects.NoOp.INSTANCE
    final target = "Hello"
    final source = new Source(SourceTypes.REQUEST_BODY, "test", target)

    when:
    def tainted = to.taint(target, Ranges.EMPTY)

    then:
    tainted == null
    to.size() == 0

    when:
    tainted = to.taintInputObject(target, source)

    then:
    tainted == null
    to.size() == 0

    when:
    tainted = to.taintInputString(target, source)

    then:
    tainted == null
    to.size() == 0

    when:
    tainted = to.get(target)

    then:
    tainted == null

    when:
    final flat = to.isFlat()

    then:
    !flat

    when:
    final iterator = to.iterator()

    then:
    !iterator.hasNext()
  }
}
