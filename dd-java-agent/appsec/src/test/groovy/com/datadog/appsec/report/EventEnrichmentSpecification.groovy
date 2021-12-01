package com.datadog.appsec.report

import com.datadog.appsec.event.data.CaseInsensitiveMap
import com.datadog.appsec.event.data.KnownAddresses
import com.datadog.appsec.gateway.AppSecRequestContext
import com.datadog.appsec.report.raw.events.AppSecEvent100
import com.datadog.appsec.report.raw.events.Rule100
import com.datadog.appsec.report.raw.events.RuleMatch100
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
    def event = new AppSecEvent100(
      eventId: 'b361b00c-d1bb-4b1b-8eda-e67ced4cda42',
      eventType: 'appsec',
      eventVersion: '1.0.0',
      rule: new Rule100(),
      ruleMatch: new RuleMatch100(),
      )
    AppSecRequestContext appSecReqCtx = Mock()
    IGSpanInfo spanInfo = Mock()
    DDId spanId = DDId.from(6666L)
    DDId traceId = DDId.from(7777L)

    when:
    EventEnrichment.enrich event, spanInfo, appSecReqCtx

    then:
    1 * appSecReqCtx.get(KnownAddresses.REQUEST_SCHEME) >> 'https'
    1 * appSecReqCtx.get(KnownAddresses.REQUEST_METHOD) >> 'POST'
    1 * appSecReqCtx.get(KnownAddresses.REQUEST_URI_RAW) >> '/foo/bar?x=1'
    1 * appSecReqCtx.get(KnownAddresses.HEADERS_NO_COOKIES) >>
      new CaseInsensitiveMap<List<String>>([
        my_headers: ['foo', 'bar'],
        host: ['example.com:8888']])
    1 * appSecReqCtx.get(KnownAddresses.REQUEST_CLIENT_IP) >> '1.2.3.4'
    1 * appSecReqCtx.get(KnownAddresses.REQUEST_CLIENT_PORT) >> 1234
    1 * appSecReqCtx.get(KnownAddresses.RESPONSE_STATUS) >> 200
    1 * appSecReqCtx.isBlocked() >> false

    1 * spanInfo.spanId >> spanId
    1 * spanInfo.traceId >> traceId

    0 * _

    event.eventId.length() == 36
    event.eventType == 'appsec'
    event.eventVersion == '1.0.0'
    event.detectedAt <= Instant.now()
    with(event.context) {
      with(http) {
        contextVersion == '1.0.0'
        with(request) {
          method == 'POST'
          url == 'https://example.com:8888/foo/bar'
          remoteIp == '1.2.3.4'
          remotePort == 1234
          headers == [
            my_headers: ['foo', 'bar'],
            host: ['example.com:8888']]
        }
        with(response) {
          status == 200
          headers == null
          blocked == false
        }
      }
      with(service) {
        contextVersion == '0.1.0'
        name: 'my service'
        environment: 'prod'
        version: '1.1.1'
      }
      with(span) {
        contextVersion == '0.1.0'
        id == spanId.toString()
      }
      with(tags) {
        contextVersion == '0.1.0'
        values == ['env:prod', 'version:1.1.1'] as Set
      }
      with(trace) {
        contextVersion == '0.1.0'
        id == traceId.toString()
      }
      with(library) {
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
