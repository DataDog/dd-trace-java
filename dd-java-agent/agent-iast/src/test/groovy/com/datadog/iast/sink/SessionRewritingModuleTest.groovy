package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.RequestEndedHandler
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.gateway.Flow
import datadog.trace.api.iast.InstrumentationBridge
import datadog.trace.api.iast.sink.InsecureAuthProtocolModule
import datadog.trace.api.iast.sink.SessionRewritingModule
import datadog.trace.api.internal.TraceSegment

class SessionRewritingModuleTest extends IastModuleImplTestBase{

  private static final REFERRER_URL = "https://localhost:8080/insecure/login.html"
  private static final JSESSIONID_URL = REFERRER_URL + ";jsessionid=1A530637289A03B07199A44E8D531427"
  private static final EVIDENCE = "URL: "+ JSESSIONID_URL
  private static final REFERRER_EVIDENCE = EVIDENCE + " Referrer: " + REFERRER_URL

  private SessionRewritingModule module

  def setup() {
    InstrumentationBridge.clearIastModules()
    module = new SessionRewritingModuleImpl(dependencies)
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

  void 'check session rewriting'() {
    given:
    final handler = new RequestEndedHandler(dependencies)
    span.getTags() >> [
      'http.status_code': status_code,
      'http.url' : url
    ]
    if(referrer != null){
      ctx.referrer = referrer
    }


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
    url | referrer | status_code | expected
    JSESSIONID_URL | REFERRER_URL | 200 | REFERRER_EVIDENCE
    JSESSIONID_URL | null | 200 | EVIDENCE
    JSESSIONID_URL | null | 300 | EVIDENCE
    JSESSIONID_URL | null | 400 | null
    REFERRER_URL | REFERRER_URL | 200 | null
  }

  void 'ignore if context is null'(){
    when:
    module.onRequestEnd(null, null)

    then:
    0 * _
  }


  private static void assertVulnerability(final Vulnerability vuln) {
    assert vuln != null
    assert vuln.getType() == VulnerabilityType.SESSION_REWRITING
    assert vuln.getLocation() != null
  }

  private static void assertEvidence(final Vulnerability vuln, final String expected) {
    assertVulnerability(vuln)
    final evidence = vuln.getEvidence()
    assert evidence != null
    assert evidence.value == expected
  }
}
