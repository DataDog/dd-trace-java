package com.datadog.iast.taint

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.Appender
import com.datadog.iast.model.RangeImpl
import com.datadog.iast.model.SourceImpl
import spock.lang.Specification
import datadog.trace.api.iast.taint.Range

class TaintedObjectsMapTest extends Specification {

  void 'test that objects are not tainted if null ranges are provided'() {
    setup:
    def toTaint = UUID.randomUUID().toString()
    def to = TaintedObjectsMap.build(TaintedMap.build(8))
    def logger = TaintedObjectsMap.LOGGER as Logger
    def appender = Mock(Appender<?>)
    logger.addAppender(appender)

    when:
    to.taint(toTaint, ranges as Range[])

    then:
    noExceptionThrown()
    if (taint) {
      to.get(toTaint) != null
      to.isTainted(toTaint)
      0 * appender.doAppend(_)
    } else {
      to.get(toTaint) == null
      !to.isTainted(toTaint)
      1 * appender.doAppend(_ as Object)
    }

    cleanup:
    logger.detachAppender(appender)

    where:
    ranges                                                                                                                       | taint
    null                                                                                                                         | false
    []                                                                                                                           | true
    [new RangeImpl(0, 10, new SourceImpl(1 as byte, 'a', 'b'), 1), null]                                                         | false
    [new RangeImpl(0, 10, new SourceImpl(1 as byte, 'a', 'b'), 1), new RangeImpl(0, 10, new SourceImpl(2 as byte, 'a', 'b'), 1)] | true
  }
}
