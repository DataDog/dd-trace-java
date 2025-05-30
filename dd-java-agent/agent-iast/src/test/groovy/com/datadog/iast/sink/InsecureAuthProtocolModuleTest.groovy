package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.RequestEndedHandler
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.TagMap
import datadog.trace.api.gateway.Flow
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.InsecureAuthProtocolModule
import datadog.trace.api.internal.TraceSegment

class InsecureAuthProtocolModuleTest extends IastModuleImplTestBase{

  private static final BASIC_EVIDENCE = 'Authorization : Basic'

  private static final DIGEST_EVIDENCE = 'Authorization : Digest'

  private static final BASIC_HEADER_VALUE = 'Basic YWxhZGRpbjpvcGVuc2VzYW1l'

  private static final DIGEST_HEADER_VALUE = 'Digest realm="testrealm@host.com", qop="auth,auth-int", nonce="dcd98b7102", opaque="5ccc069c"'

  private InsecureAuthProtocolModule module

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = new InsecureAuthProtocolModuleImpl(dependencies)
    InstrumentationBridge.registerIastModule(module)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  @Override
  protected TraceSegment buildTraceSegment() {
    return Mock(TraceSegment)
  }

  void 'check header value injection'() {
    given:
    final handler = new RequestEndedHandler(dependencies)
    ctx.authorization = value
    span.getTags() >> TagMap.fromMap([
      'http.status_code': status_code
    ])

    when:
    def flow = handler.apply(reqCtx, span)

    then:
    flow.getAction() == Flow.Action.Noop.INSTANCE
    flow.getResult() == null
    1 * traceSegment.setTagTop("_dd.iast.enabled", 1)
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    value | status_code | expected
    'anyValue' | 200i | null
    BASIC_HEADER_VALUE | 200i | BASIC_EVIDENCE
    DIGEST_HEADER_VALUE| 200i | DIGEST_EVIDENCE
    DIGEST_HEADER_VALUE| 302i | DIGEST_EVIDENCE
    BASIC_HEADER_VALUE | 404i | null
    DIGEST_HEADER_VALUE| 404i | null
  }

  void 'ignore if context is null'(){
    when:
    module.onRequestEnd(null, null)

    then:
    0 * _
  }


  private static void assertVulnerability(final Vulnerability vuln) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.INSECURE_AUTH_PROTOCOL
    assert vuln.getLocation() != null
  }

  private static void assertEvidence(final Vulnerability vuln, final String expected) {
    assertVulnerability(vuln)
    final evidence = vuln.getEvidence()
    assert evidence != null
    assert evidence.value == expected
  }
}
