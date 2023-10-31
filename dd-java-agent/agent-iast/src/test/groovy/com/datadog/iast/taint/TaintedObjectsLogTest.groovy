package com.datadog.iast.taint

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.datadog.iast.IastSystem
import com.datadog.iast.model.Source
import datadog.trace.api.iast.SourceTypes
import datadog.trace.test.util.DDSpecification
import groovy.transform.CompileDynamic

@CompileDynamic
class TaintedObjectsLogTest extends DDSpecification {

  private boolean defaultDebug
  private Logger logger
  private Level defaultLevel

  void setup() {
    defaultDebug = IastSystem.DEBUG
    logger = TaintedObjects.TaintedObjectsDebugAdapter.LOGGER as Logger
    defaultLevel = logger.getLevel()
  }

  void cleanup() {
    IastSystem.DEBUG = defaultDebug
    logger.setLevel(defaultLevel)
  }

  void "test TaintedObjects debug log"() {
    given:
    IastSystem.DEBUG = true
    logger.setLevel(Level.ALL)
    final taintedObjects = TaintedObjects.build(1024) as TaintedObjects.TaintedObjectsDebugAdapter
    final value = "A"

    when:
    def tainted = taintedObjects.taint(value, Ranges.forCharSequence(value, new Source(SourceTypes.NONE, null, null)))

    then:
    noExceptionThrown()
    tainted != null

    when:
    tainted = taintedObjects.get(value)

    then:
    noExceptionThrown()
    tainted != null

    when:
    final mockTainted = Mock(TaintedObject) {
      get() >> { throw new Error('Boom!!!!') }
    }
    taintedObjects.logTainted(mockTainted)

    then:
    noExceptionThrown()
  }

  void "test TaintedObjects debug log on clear"() {
    given:
    IastSystem.DEBUG = true
    logger.level = Level.ALL
    TaintedObjects taintedObjects = TaintedObjects.build(1024)
    final obj = 'A'
    taintedObjects.taint(obj, Ranges.forCharSequence(obj, new Source(SourceTypes.NONE, null, null)))

    when:
    taintedObjects.clear()

    then:
    noExceptionThrown()
  }
}

