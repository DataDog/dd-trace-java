package com.datadog.iast.taint

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.datadog.iast.IastSystem
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import datadog.trace.test.util.DDSpecification

class TaintedObjectsLogTest extends DDSpecification {

  private boolean defaultDebug
  private Logger logger
  private Level defaultLevel

  def setup() {
    defaultDebug = IastSystem.DEBUG
    logger = TaintedObjects.TaintedObjectsDebugAdapter.LOGGER as Logger
    defaultLevel = logger.getLevel()
  }

  def cleanup() {
    IastSystem.DEBUG = defaultDebug
    logger.setLevel(defaultLevel)
  }

  def "test TaintedObjects debug log"() {
    given:
    IastSystem.DEBUG = true
    logger.setLevel(Level.ALL)
    TaintedObjects taintedObjects = TaintedObjects.build()
    final value = "A"

    when:
    def tainted = taintedObjects.taintInputString(value, new Source(SourceType.NONE, null, null))

    then:
    noExceptionThrown()
    tainted != null

    when:
    tainted = taintedObjects.get(value)

    then:
    noExceptionThrown()
    tainted != null
  }

  def "test TaintedObjects debug log on release"() {
    given:
    IastSystem.DEBUG = true
    logger.setLevel(Level.ALL)
    TaintedObjects taintedObjects = TaintedObjects.build()
    taintedObjects.taint("A", [new Range(0, 1, new Source(SourceType.NONE, null, null))] as Range[])
    taintedObjects.taintInputString("B", new Source(SourceType.REQUEST_PARAMETER_NAME, 'test', 'value'))

    when:
    taintedObjects.release()

    then:
    noExceptionThrown()
  }
}

