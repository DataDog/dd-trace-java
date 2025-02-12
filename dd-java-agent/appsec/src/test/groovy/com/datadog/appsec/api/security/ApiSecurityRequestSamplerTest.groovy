package com.datadog.appsec.api.security

import com.datadog.appsec.gateway.AppSecRequestContext
import datadog.trace.api.Config
import datadog.trace.test.util.DDSpecification

class ApiSecurityRequestSamplerTest extends DDSpecification {

  def config = Mock(Config) {
    isApiSecurityEnabled() >> true
  }

  def sampler = new ApiSecurityRequestSampler(config)

  void 'Api Security Sample Request'() {
    when:
    def span = Mock(AppSecRequestContext) {
      getRoute() >> route
      getMethod() >> method
      getResponseStatus() >> statusCode
    }
    def sample = sampler.sampleRequest(span)

    then:
    sample == sampleResult

    where:
    method | route    | statusCode | sampleResult
    'GET'  | 'route1' | 200  | true
    'GET'  | 'route2' | null | false
    'GET'  | null     | 404  | false
    'TOP'  | 999      | 404  | true
    null   | '999'    | 404  | false
  }
}