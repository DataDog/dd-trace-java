package com.datadog.iast.taint

import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import datadog.trace.test.util.DDSpecification

class TaintObjectsLogTest extends DDSpecification {
  def "test TaintedObjects debug log"() {
    given:
    TaintedObjects taintedObjects = new TaintedObjects()


    when:
    taintedObjects.setDebug(true)
    taintedObjects.taintInputString("A", new Source(SourceType.NONE, null, null))

    then:
    noExceptionThrown()
  }
}

