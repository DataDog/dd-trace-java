package com.datadog.appsec.report

import com.datadog.appsec.event.data.CaseInsensitiveMap
import com.datadog.appsec.event.data.DataBundle
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.report.raw.events.attack.Attack010
import com.datadog.appsec.report.raw.events.attack._definitions.rule.Rule010
import com.datadog.appsec.report.raw.events.attack._definitions.rule_match.RuleMatch010
import datadog.trace.api.DDId
import datadog.trace.api.config.GeneralConfig
import datadog.trace.api.gateway.IGSpanInfo
import datadog.trace.test.util.DDSpecification

import java.time.Instant

class EventEnrichmentSpecification extends DDSpecification {

  void 'fills in missing fields'() {
    injectSysConfig(GeneralConfig.SERVICE_NAME, 'my service')
    injectSysConfig(GeneralConfig.ENV, 'prod')
    injectSysConfig(GeneralConfig.VERSION, '1.1.1')
    def attack = new Attack010(
      type: 'waf',
      blocked: Boolean.TRUE,
      rule: new Rule010(),
      ruleMatch: new RuleMatch010(),
      )
    DataBundle dataBundle = Mock()
    IGSpanInfo spanInfo = Mock()
    DDId spanId = DDId.from(6666L)
    DDId traceId = DDId.from(7777L)

    when:
    EventEnrichment.enrich attack, spanInfo, dataBundle

    then:
    1 * dataBundle.get(KnownAddresses.REQUEST_SCHEME) >> 'https'
    1 * dataBundle.get(KnownAddresses.REQUEST_METHOD) >> 'POST'
    1 * dataBundle.get(KnownAddresses.REQUEST_URI_RAW) >> '/foo/bar?x=1'
    1 * dataBundle.get(KnownAddresses.HEADERS_NO_COOKIES) >>
      new CaseInsensitiveMap<List<String>>([
        my_headers: ['foo', 'bar'],
        host: ['example.com:8888']])
    1 * dataBundle.get(KnownAddresses.REQUEST_CLIENT_IP) >> '1.2.3.4'
    1 * dataBundle.get(KnownAddresses.REQUEST_CLIENT_PORT) >> 1234

    1 * spanInfo.spanId >> spanId
    1 * spanInfo.tags >> [foo: 'bar']
    1 * spanInfo.traceId >> traceId

    0 * _

    attack.eventId.length() == 36
    attack.eventType == 'appsec.threat.attack'
    attack.eventVersion == '0.1.0'
    attack.detectedAt <= Instant.now()
    with(attack.context) {
      with(http) {
        contextVersion == '0.1.0'
        with(request) {
          scheme == 'https'
          method == 'POST'
          url == 'https://example.com:8888/foo/bar'
          host == 'example.com'
          port == 8888
          path == '/foo/bar'
          remoteIp == '1.2.3.4'
          remotePort == 1234
          headers.headerMap == [
            my_headers: ['foo', 'bar'],
            host: ['example.com:8888']]
        }
      }
      with(service) {
        properties == [
          environment: 'prod',
          context_version: '0.1.0',
          name: 'my service',
          version: '1.1.1'
        ]
      }
      with(serviceStack) {
        contextVersion == '0.1.0'
        services == [attack.context.service]
      }
      with(span) {
        contextVersion == '0.1.0'
        id == spanId.toString()
      }
      with(tags) {
        contextVersion == '0.1.0'
        values == ['foo:bar'] as Set
      }
      with(trace) {
        contextVersion == '0.1.0'
        id == traceId.toString()
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
