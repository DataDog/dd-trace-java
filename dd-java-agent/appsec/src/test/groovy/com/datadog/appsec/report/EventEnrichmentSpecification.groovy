package com.datadog.appsec.report

import com.datadog.appsec.event.data.CaseInsensitiveMap
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.report.raw.events.attack.Attack010
import com.datadog.appsec.report.raw.events.attack._definitions.rule.Rule010
import com.datadog.appsec.report.raw.events.attack._definitions.rule_match.RuleMatch010
import spock.lang.Specification

import java.time.Instant

class EventEnrichmentSpecification extends Specification {

  void 'fills in missing fields'() {
    def attack = new Attack010(
      type: 'waf',
      blocked: Boolean.TRUE,
      rule: new Rule010(),
      ruleMatch: new RuleMatch010(),
      )
    DataBundle dataBundle = Mock()

    when:
    EventEnrichment.enrich attack, dataBundle

    then:
    1 * dataBundle.get(KnownAddresses.REQUEST_URI_RAW) >> '/foo/bar?x=1'
    1 * dataBundle.get(KnownAddresses.HEADERS_NO_COOKIES) >>
      new CaseInsensitiveMap<List<String>>([my_headers: ['foo', 'bar']])

    attack.eventId.length() == 36
    attack.eventType == 'appsec.threat.attack'
    attack.eventVersion == '0.1.0'
    attack.detectedAt <= Instant.now()
    with(attack.context) {
      with(http) {
        contextVersion == '0.1.0'
        with(request) {
          scheme == 'http'
          method == 'GET'
          url == 'http://example.com/'
          host == 'example.com'
          port == 80
          path == '/foo/bar'
          remoteIp == '255.255.255.255'
          remotePort == 65535
          headers.headerMap == [
            my_headers: ['foo', 'bar']]
        }
      }
      with(tracer) {
        contextVersion == '0.1.0'
        runtimeType == 'java'
        runtimeVersion != null
        libVersion != null
      }
      with(host) {
        contextVersion == '0.1.0'
        osType != null
        hostname != null
      }
    }
  }
}
