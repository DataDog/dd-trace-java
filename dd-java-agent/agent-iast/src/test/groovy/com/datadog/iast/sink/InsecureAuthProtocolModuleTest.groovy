package com.datadog.iast.sink

import com.datadog.iast.IastModuleImplTestBase
import com.datadog.iast.Reporter
import com.datadog.iast.model.Vulnerability
import com.datadog.iast.model.VulnerabilityType
import datadog.trace.api.iast.sink.InsecureAuthProtocolModule

class InsecureAuthProtocolModuleTest extends IastModuleImplTestBase{

  private static final BASIC_EVIDENCE = 'Found Authorization Basic in header'

  private static final DIGEST_EVIDENCE = 'Found Authorization Digest in header'

  private static final BASIC_HEADER_VALUE = 'Basic YWxhZGRpbjpvcGVuc2VzYW1l'

  private static final DIGEST_HEADER_VALUE = 'Digest realm="testrealm@host.com", qop="auth,auth-int", nonce="dcd98b7102", opaque="5ccc069c"'

  private InsecureAuthProtocolModule module

  def setup() {
    module = new InsecureAuthProtocolModuleImpl(dependencies)
  }

  @Override
  protected Reporter buildReporter() {
    return Mock(Reporter)
  }

  void 'check header value injection'() {
    when:
    module.onHeader(name, value)

    then:
    if (expected != null) {
      1 * reporter.report(_, _) >> { args -> assertEvidence(args[1] as Vulnerability, expected) }
    } else {
      0 * reporter.report(_, _)
    }

    where:
    name | value | expected
    'anyHeader' | 'anyValue' | null
    'Authorization' | BASIC_HEADER_VALUE | BASIC_EVIDENCE
    'AuthORization' | BASIC_HEADER_VALUE | BASIC_EVIDENCE
    'anyHeader' | BASIC_HEADER_VALUE | null
    'Authorization' | DIGEST_HEADER_VALUE | DIGEST_EVIDENCE
    'anyHeader' | DIGEST_HEADER_VALUE | null
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
