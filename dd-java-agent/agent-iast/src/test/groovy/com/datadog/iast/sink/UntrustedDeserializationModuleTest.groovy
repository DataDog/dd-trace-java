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

  void 'test null value with object null'() {
    when:
    module.onObject(null)

    then:
    0 * _
  }

  void 'test untrusted deserialization detection' () {
    setup:
    def object = Mock(Object)

    when:
    module.onObject(object)

    then: 'without tainted object'
    0 * reporter.report(_, _)

    when:
    taint(object)
    module.onObject(object)

    then: 'with tainted object'
    1 * reporter.report(_, { Vulnerability vul -> vul.type == VulnerabilityType.UNTRUSTED_DESERIALIZATION})
  }

  private void taint(final Object value) {
    ctx.getTaintedObjects().taint(value, Ranges.forObject(new Source(SourceTypes.REQUEST_BODY, 'name', value.toString())))
  }
}
