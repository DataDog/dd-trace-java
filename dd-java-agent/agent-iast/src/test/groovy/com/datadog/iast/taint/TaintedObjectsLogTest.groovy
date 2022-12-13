package com.datadog.iast.taint

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import datadog.trace.test.util.DDSpecification

class TaintedObjectsLogTest extends DDSpecification {

  private boolean defaultDebug
  private Logger logger
  private Level defaultLevel

  def setup() {
    defaultDebug = TaintedObjects.DEBUG
    logger = TaintedObjects.LOGGER as Logger
    defaultLevel = logger.getLevel()
  }

  def cleanup() {
    TaintedObjects.DEBUG = defaultDebug
    logger.setLevel(defaultLevel)
  }

  def "test TaintedObjects debug log"() {
    given:
    TaintedObjects.setDebug(true)
    logger.setLevel(Level.ALL)
    TaintedObjects taintedObjects = new TaintedObjects()

    when:
    taintedObjects.taintInputString("A", new Source(SourceType.NONE, null, null))

    then:
    noExceptionThrown()
  }

  def "test TaintedObjects debug log on release"() {
    given:
    TaintedObjects.setDebug(true)
    logger.setLevel(Level.ALL)
    TaintedObjects taintedObjects = new TaintedObjects()
    taintedObjects.taint("A", [new Range(0, 1, new Source(SourceType.NONE, null, null))] as Range[])
    taintedObjects.taintInputString("B", new Source(SourceType.REQUEST_PARAMETER_NAME, 'test', 'value'))

    when:
    taintedObjects.release()

    then:
    noExceptionThrown()
  }
}

