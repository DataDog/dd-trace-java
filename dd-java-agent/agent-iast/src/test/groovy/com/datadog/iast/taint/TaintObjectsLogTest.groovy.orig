package com.datadog.iast.taint

import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import datadog.trace.test.util.DDSpecification
import org.slf4j.Logger

import java.lang.reflect.Field
import java.lang.reflect.Modifier

class TaintObjectsLogTest extends DDSpecification {
  def "test TaintedObjects debug log"() {
    given:
    Field field = TaintedObjects.getDeclaredField("log")
    field.setAccessible(true)

    Field modifiersField = field.class.getDeclaredField("modifiers")
    modifiersField.setAccessible(true)
    modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL)

    Logger log = Mock(Logger)
    TaintedObjects taintedObjects = new TaintedObjects()
    field.set(taintedObjects, log)

    when:
    taintedObjects.setDebug(true)
    taintedObjects.taintInputString("A", new Source(SourceType.NONE, null, null))

    then:
    1 * taintedObjects.log.debug("TaintedObjects debug mode is true")
    1 * taintedObjects.log.debug("TaintInputString: A")
  }
}
