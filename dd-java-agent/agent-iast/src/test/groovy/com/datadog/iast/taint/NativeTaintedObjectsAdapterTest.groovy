package com.datadog.iast.taint

import datadog.trace.api.iast.taint.TaintedObjects
import spock.lang.Specification

class NativeTaintedObjectsAdapterTest extends Specification {

  void 'test that the adapter falls back to the delegate for non tainted objects'() {
    setup:
    final delegate = Mock(TaintedObjects)
    final to = new NativeTaintedObjectsAdapter({ delegate })

    when:
    to.taint(new Object())

    then:
    1 * delegate.taint(_, _)

    when:
    to.get(new Object())

    then:
    1 * delegate.get(_)

    when:
    to.isTainted(new Object())

    then:
    1 * delegate.isTainted(_)

    when:
    to.count()

    then:
    1 * delegate.count()

    when:
    to.clear()

    then:
    1 * delegate.clear()
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
