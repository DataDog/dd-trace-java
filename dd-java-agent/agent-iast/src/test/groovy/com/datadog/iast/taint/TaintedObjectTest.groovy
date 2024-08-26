package com.datadog.iast.taint

import ch.qos.logback.classic.Logger
import ch.qos.logback.core.Appender
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import datadog.trace.api.Config
import spock.lang.Specification

import static datadog.trace.api.iast.SourceTypes.REQUEST_HEADER_NAME
import static datadog.trace.api.iast.VulnerabilityMarks.NOT_MARKED

class TaintedObjectTest extends Specification {

  void 'test that tainted objects never go over the limit'() {
    given:
    final max = Config.get().iastMaxRangeCount
    final ranges = (0..max + 1)
    .collect { index -> new Range(index, 1, new Source(REQUEST_HEADER_NAME, 'a', 'b'), NOT_MARKED) }

    when:
    final tainted = new TaintedObject('test', ranges.toArray(new Range[0]))

    then:
    ranges.size() > max
    tainted.ranges.size() == max
    tainted.toString().contains("${tainted.ranges.size()} ranges")
  }

  void 'test that objects are not tainted if null ranges are provided'() {
    setup:
    def toTaint = UUID.randomUUID().toString()
    def to = new TaintedObject(toTaint, Ranges.forCharSequence(toTaint, new Source(1 as byte, 'a', 'b'), NOT_MARKED))
    def logger = TaintedObject.LOGGER as Logger
    def appender = Mock(Appender<?>)
    logger.addAppender(appender)

    when:
    to.setRanges(ranges as Range[])

    then:
    noExceptionThrown()
    if (taint) {
      to.ranges.length == ranges.size()
      0 * appender.doAppend(_)
    } else {
      to.ranges.length == 1
      1 * appender.doAppend(_ as Object)
    }

    cleanup:
    logger.detachAppender(appender)

    where:
    ranges                                                                                                       | taint
    null                                                                                                         | false
    []                                                                                                           | true
    [new Range(0, 10, new Source(1 as byte, 'a', 'b'), 1), null]                                                 | false
    [new Range(0, 10, new Source(1 as byte, 'a', 'b'), 1), new Range(0, 10, new Source(2 as byte, 'a', 'b'), 1)] | true
  }
}
