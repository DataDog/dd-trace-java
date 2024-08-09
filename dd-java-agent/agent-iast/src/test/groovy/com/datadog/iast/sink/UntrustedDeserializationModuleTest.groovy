package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Source
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import com.datadog.iast.taint.Ranges
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.sink.UntrustedDeserializationModule

class UntrustedDeserializationModuleTest extends IastModuleImplTestBase {

  private UntrustedDeserializationModule module

  def setup() {
    module = new UntrustedDeserializationModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'test null value with input stream null'() {
    when:
    module.onInputStream(null)

    then:
    0 * _
  }

  void 'test null value with reader null'() {
    when:
    module.onReader(null)

    then:
    0 * _
  }

  void 'test null value with string null'() {
    when:
    module.onString(null)

    then:
    0 * _
  }

  void 'test untrusted deserialization detection with input stream' () {
    setup:
    def inputStream = Mock(InputStream)

    when:
    module.onInputStream(inputStream)

    then: 'without tainted input stream'
    0 * reporter.report(_, _)

    when:
    taint(inputStream)
    module.onInputStream(inputStream)

    then: 'with tainted input stream'
    1 * reporter.report(_, { Vulnerability vul -> vul.type == VulnerabilityType.UNTRUSTED_DESERIALIZATION})
  }

  void 'test untrusted deserialization detection with reader' () {
    setup:
    def reader = Mock(Reader)

    when:
    module.onReader(reader)

    then: 'without tainted reader'
    0 * reporter.report(_, _)

    when:
    taint(reader)
    module.onReader(reader)

    then: 'with tainted reader'
    1 * reporter.report(_, { Vulnerability vul -> vul.type == VulnerabilityType.UNTRUSTED_DESERIALIZATION})
  }

  void 'test untrusted deserialization detection with string' () {
    setup:
    def string = "test"

    when:
    module.onString(string)

    then: 'without tainted string'
    0 * reporter.report(_, _)

    when:
    taint(string)
    module.onString(string)

    then: 'with tainted string'
    1 * reporter.report(_, { Vulnerability vul -> vul.type == VulnerabilityType.UNTRUSTED_DESERIALIZATION})
  }

  private void taint(final Object value) {
    ctx.getTaintedObjects().taint(value, Ranges.forObject(new Source(SourceTypes.REQUEST_BODY, 'name', value.toString())))
  }
}
