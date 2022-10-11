package com.datadog.iast

import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.SourceType
import com.datadog.iast.taint.TaintedObject
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.RealCallThrowable
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import java.util.regex.Matcher
import java.util.regex.Pattern

class IastModuleImplOnStringFormatTest extends IastModuleImplTestBase {

  IastRequestContext ctx = new IastRequestContext()

  void setup() {
    tracer.activeSpan() >> Mock(AgentSpan) {
      getRequestContext() >> Mock(RequestContext) {
        getData(RequestContextSlot.IAST) >> ctx
      }
    }
  }

  void 'onStringConcat fmt=#fmt args=#args'() {
    when:
    String result = run(fmt, args)

    then:
    addTaintSep(result) == exp

    where:
    fmt                | args                   | exp
    'Hello| World|'    | []                     | 'Hello| World|'
    '%s %s'            | ['|Hello|', 'W|orl|d'] | '|Hello| W|orl|d'
    'H|ello| %s|!|'    | ['Worl|d|']            | 'H|ello| Worl|d||!|'
    'Hello %|s|'       | ['World']              | '|Hello World|'
    '%.5s World'       | ['H|elloooooo|o']      | 'Hello World' // XFAIL: 'Hello' is created internally
    'H|el||l||||o |%s' | ['World']              | 'H|el||l||||o |World' // adjacent & 0-length ranges
  }

  void 'exercise path of TaintedTrackingAppendable not otherwise reachable'() {
    setup:
    def appendable = new IastModuleImpl.TaintedTrackingAppendable(ctx.taintedObjects, [:])
    String strToAppend = '1234567890'

    when:
    ctx.taintedObjects.taint(strToAppend, new Range(0, 4, null))
    appendable.append(strToAppend, 0, 5)
    appendable.append(strToAppend, 4, 5)
    appendable.append(strToAppend, 0, 0)

    then:
    appendable.taintedRanges.size() == 1
    appendable.taintedRanges[0] == new Range(0, 4, null)
  }

  void 'fails with overlapping ranges'() {
    setup:
    String fmt = 'Hello %s'
    Source src = new Source(SourceType.REQUEST_PATH, 'foo', fmt)
    ctx.taintedObjects.taint(fmt, new Range(0, 5, src), new Range(4, 1, src))

    when:
    module.onStringFormat(null, fmt, 'World')

    then:
    thrown IllegalStateException
  }

  void 'exception during the real call results in RealCallThrowable'() {
    setup:
    String fmt = 'Hello %s'
    Formattable f = Mock()

    when:
    module.onStringFormat(null, fmt, f)

    then:
    1 * f.formatTo(*_) >> { throw new RuntimeException('foo') }
    RealCallThrowable rct = thrown RealCallThrowable
    rct.cause.message == 'foo'
  }

  private String run(String fmt, List objects) {
    String newFmt = doTaint 'format', fmt
    int i = 1
    List<String> newObjects = objects.collect { it -> doTaint "param ${i++}", it }
    module.onStringFormat(null, newFmt, newObjects as Object[])
  }

  private String doTaint(String name, String s) {
    Pattern p = ~/\|([^|]*)\|/
    Matcher matcher = p.matcher(s)
    def ranges = []
    int offset = 0
    String newS = s.replace('|', '')
    Source source = new Source(SourceType.REQUEST_PATH, name, newS)
    while (matcher.find()) {
      ranges << new Range(matcher.start() - offset, matcher.group(1).length(), source)
      offset += 2
    }
    if (ranges.size() > 0) {
      ctx.taintedObjects.taint(newS, ranges as Range[])
      newS
    } else {
      s
    }
  }

  private String addTaintSep(String s) {
    TaintedObject to = ctx.taintedObjects.get(s)
    if (!to) {
      s
    } else {
      def ranges =
        to.ranges.collect { [it.start, it.start + it.length] }.sort { it[0]}
      def sb = new StringBuilder(s)
      int offset = 0
      ranges.each {
        sb.insert(it[0] + (offset++), '|')
        sb.insert(it[1] + (offset++), '|')
      }
      sb.toString()
    }
  }
}
