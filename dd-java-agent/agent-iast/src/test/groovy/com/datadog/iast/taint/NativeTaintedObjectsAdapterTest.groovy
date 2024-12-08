package com.datadog.iast.taint

import datadog.trace.api.iast.taint.TaintedObjects
import spock.lang.Specification

class NativeTaintedObjectsAdapterTest extends Specification {

  void 'test that the adapter fails without the native library'() {
    setup:
    final delegate = TaintedObjectsMap.build(TaintedMap.build(8))
    final to = new NativeTaintedObjectsAdapter({ delegate })

    when:
    to.taint(new Object())

    then:
    thrown(UnsatisfiedLinkError)

    when:
    to.get(new Object())

    then:
    thrown(UnsatisfiedLinkError)

    when:
    to.isTainted(new Object())

    then:
    thrown(UnsatisfiedLinkError)

    when:
    final count = to.count()
    final size = to.size()

    then:
    count == 0
    size == 0

    when:
    to.clear()

    then:
    to.size() == 0
  }

  void 'test that the adapter resolves to NoOp when no delegate is provided'() {
    setup:
    final to = new NativeTaintedObjectsAdapter({ null })

    when:
    final size = to.count()

    then:
    size == 0
    to.delegate == TaintedObjects.NoOp.INSTANCE
  }
}
