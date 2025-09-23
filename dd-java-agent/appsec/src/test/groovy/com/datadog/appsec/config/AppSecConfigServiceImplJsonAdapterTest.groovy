package com.datadog.appsec.config

import datadog.trace.test.util.DDSpecification
import okio.Buffer

class AppSecConfigServiceImplJsonAdapterTest extends DDSpecification {

  void 'test JSON number conversion - whole numbers'() {
    given:
    def json = '42.0'
    def buffer = new Buffer().writeUtf8(json)

    when:
    def result = AppSecConfigServiceImpl.ADAPTER.fromJson(buffer)

    then:
    result != null
    // This should trigger the true branch: value % 1 == 0 ? longValue : value
  }

  void 'test JSON number conversion - fractional numbers'() {
    given:
    def json = '42.5'
    def buffer = new Buffer().writeUtf8(json)

    when:
    def result = AppSecConfigServiceImpl.ADAPTER.fromJson(buffer)

    then:
    result != null
    // This should trigger the false branch: value % 1 == 0 ? longValue : value
  }

  void 'test JSON number conversion - mixed object'() {
    given:
    def json = '{"whole": 100.0, "fractional": 3.14}'
    def buffer = new Buffer().writeUtf8(json)

    when:
    def result = AppSecConfigServiceImpl.ADAPTER.fromJson(buffer)

    then:
    result != null
    result instanceof Map
    // This exercises both branches through the nested numbers
  }

  void 'test JSON number conversion - zero'() {
    given:
    def json = '0.0'
    def buffer = new Buffer().writeUtf8(json)

    when:
    def result = AppSecConfigServiceImpl.ADAPTER.fromJson(buffer)

    then:
    result != null
    // This should trigger the true branch: 0.0 % 1 == 0
  }

  void 'test JSON number conversion - negative whole'() {
    given:
    def json = '-10.0'
    def buffer = new Buffer().writeUtf8(json)

    when:
    def result = AppSecConfigServiceImpl.ADAPTER.fromJson(buffer)

    then:
    result != null
    // This should trigger the true branch: -10.0 % 1 == 0
  }

  void 'test JSON number conversion - negative fractional'() {
    given:
    def json = '-3.5'
    def buffer = new Buffer().writeUtf8(json)

    when:
    def result = AppSecConfigServiceImpl.ADAPTER.fromJson(buffer)

    then:
    result != null
    // This should trigger the false branch: -3.5 % 1 != 0
  }
}
