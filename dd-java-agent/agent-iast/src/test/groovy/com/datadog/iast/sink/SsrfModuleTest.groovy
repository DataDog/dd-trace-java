package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Range
import com.datadog.iast.model.Source
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.taint.Ranges
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.VulnerabilityMarks
import datadog.trace.api.iast.sink.SsrfModule

class SsrfModuleTest extends IastModuleImplTestBase {

  private SsrfModule module

  def setup() {
    module = new SsrfModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'test SSRF detection'() {
    when:
    module.onURLConnection(url)

    then: 'report is not called if no active span'
    tracer.activeSpan() >> null
    0 * reporter.report(_, _)

    when:
    module.onURLConnection(url)

    then: 'report is not called if url is not tainted'
    tracer.activeSpan() >> span
    0 * reporter.report(_, _)

    when:
    taint(url)
    module.onURLConnection(url)

    then: 'report is called when the url is tainted'
    tracer.activeSpan() >> span
    1 * reporter.report(span, { Vulnerability vul -> vul.type == VulnerabilityType.SSRF })

    where:
    url                        | _
    new URL('http://test.com') | _
    'http://test.com'          | _
  }

  void 'If all ranges from tainted element have ssfr mark vulnerability is not reported'() {
    given:
    final value = new URL('http://test.com')
    final Range[] ranges = [
      new Range(0, 2, new Source(SourceTypes.REQUEST_HEADER_VALUE, 'name1', 'value'), VulnerabilityMarks.SSRF_MARK),
      new Range(4, 1, new Source(SourceTypes.REQUEST_PARAMETER_NAME, 'name2', 'value'), VulnerabilityMarks.SSRF_MARK)
    ]
    ctx.getTaintedObjects().taint(value, ranges)

    when:
    module.onURLConnection(value)

    then:
    0 * reporter.report(_, _)
  }

  void 'test SSRF detection for host and uri'() {
    when:
    module.onURLConnection(value, host, uri)

    then: 'report is not called if no active span'
    tracer.activeSpan() >> null
    0 * reporter.report(_, _)

    when:
    module.onURLConnection(value, host, uri)

    then: 'report is not called if host or uri are not tainted'
    tracer.activeSpan() >> span
    0 * reporter.report(_, _)

    when:
    taint(host!=null ? host : uri)
    module.onURLConnection(value, host, uri)

    then: 'report is called when the host or uri are tainted'
    tracer.activeSpan() >> span
    1 * reporter.report(span, {
      Vulnerability vul -> vul.type == VulnerabilityType.SSRF
      && vul.evidence.value == value
      && vul.evidence.ranges.length == 1
      && vul.evidence.ranges[0].start == 0
      && vul.evidence.ranges[0].length == value.length()
    })


    where:
    value                        | host | uri
    'http://test.com' | new Object() | new URI('http://test.com/tested')
    'http://test.com' | null | new URI('http://test.com/tested')
  }

  private void taint(final Object value) {
    ctx.getTaintedObjects().taint(value, Ranges.forObject(new Source(SourceTypes.REQUEST_PARAMETER_VALUE, 'name', value.toString())))
  }
}
