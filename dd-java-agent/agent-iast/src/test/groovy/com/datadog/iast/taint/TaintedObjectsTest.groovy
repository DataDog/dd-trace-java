package com.datadog.iast.taint

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.Appender
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import spock.lang.Specification

class TaintedObjectsTest extends Specification {

  void 'test that objects are not tainted if null ranges are provided'() {
    setup:
    def toTaint = UUID.randomUUID().toString()
    def to = TaintedObjects.build(TaintedMap.build(8))
    def logger = TaintedObjects.LOGGER as Logger
    def appender = Mock(Appender<?>)
    logger.addAppender(appender)

    when:
    to.taint(toTaint, ranges as Range[])

    then:
    noExceptionThrown()
    if (taint) {
      to.get(toTaint) != null
      0 * appender.doAppend(_)
    } else {
      to.get(toTaint) == null
      1 * appender.doAppend(_ as Object)
    }

    cleanup:
    logger.detachAppender(appender)

    where:
    ranges                                                                                                       | taint
    null                                                                                                         | false
    []                                                                                                           | false
    [new Range(0, 10, new Source(1 as byte, 'a', 'b'), 1), null]                                                 | false
    [new Range(0, 10, new Source(1 as byte, 'a', 'b'), 1), new Range(0, 10, new Source(2 as byte, 'a', 'b'), 1)] | true
  }
}
