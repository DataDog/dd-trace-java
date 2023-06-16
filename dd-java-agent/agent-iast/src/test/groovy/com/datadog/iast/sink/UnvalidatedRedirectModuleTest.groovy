package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.IastRequestContext
import com.datadog.iast.model.Source
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.RequestContext
import datadog.trace.api.gateway.RequestContextSlot
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.SourceTypes
import datadog.trace.api.iast.sink.UnvalidatedRedirectModule
import datadog.trace.bootstrap.instrumentation.api.AgentSpan

import static com.datadog.iast.taint.TaintUtils.addFromTaintFormat
import static com.datadog.iast.taint.TaintUtils.taintFormat

class UnvalidatedRedirectModuleTest extends IastModuleImplTestBase {

  private UnvalidatedRedirectModule module

  private List<Object> objectHolder

  private IastRequestContext ctx

  def setup() {
    module = registerDependencies(new UnvalidatedRedirectModuleImpl())
    objectHolder = []
    ctx = new IastRequestContext()
    final reqCtx = Mock(RequestContext) {
      getData(RequestContextSlot.IAST) >> ctx
    }
    final span = Mock(AgentSpan) {
      getSpanId() >> 123456
      getRequestContext() >> reqCtx
    }
    tracer.activeSpan() >> span
    overheadController.consumeQuota(_, _) >> true
  }

  void 'iast module detects String redirect (#value)'(final String value, final String expected) {
    setup:
    final param = mapTainted(value)

    when:
    module.onRedirect(param)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    value        | expected
    null         | null
    '/var'       | null
    '/==>var<==' | "/==>var<=="
  }

  void 'iast module detects URI redirect (#value)'(final URI value, final String expected) {
    setup:
    ctx.taintedObjects.taintInputObject(value, new Source(SourceTypes.NONE, null, null))

    when:
    module.onURIRedirect(value)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertVulnerability(args[1] as Vulnerability) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    value                                | expected
    null                                 | null
    new URI("http://dummy.location.com") | true
  }

  void 'iast module detects String redirect with class and method (#value)'(final String value, final String expected) {
    setup:
    final param = mapTainted(value)
    final clazz = "class"
    final method = "method"

    when:
    module.onRedirect(param, clazz, method)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    value        | expected
    null         | null
    '/var'       | null
    '/==>var<==' | "/==>var<=="
  }

  void 'if onHeader receives a Location header call onRedirect'() {
    setup:
    final urm = Spy(UnvalidatedRedirectModuleImpl)
    InstrumentationBridge.registerIastModule(urm)

    when:
    urm.onHeader(headerName, "value")

    then:
    expected * urm.onRedirect("value")

    where:
    headerName | expected
    "blah"     | 0
    "Location" | 1
    "location" | 1
  }

  private String mapTainted(final String value) {
    final result = addFromTaintFormat(ctx.taintedObjects, value)
    objectHolder.add(result)
    return result
  }

  private static void assertVulnerability(final Vulnerability vuln) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.UNVALIDATED_REDIRECT
    assert vuln.getLocation() != null
  }

  private static void assertEvidence(final Vulnerability vuln, final String expected) {
    assertVulnerability(vuln)
    final evidence = vuln.getEvidence()
    assert evidence != null
    final formatted = taintFormat(evidence.getValue(), evidence.getRanges())
    assert formatted == expected
  }
}
