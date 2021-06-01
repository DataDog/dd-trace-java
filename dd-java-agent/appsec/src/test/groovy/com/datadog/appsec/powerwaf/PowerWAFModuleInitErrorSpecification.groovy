package com.datadog.appsec.powerwaf

import spock.lang.Specification

class PowerWAFModuleInitErrorSpecification extends Specification {
  void 'bad resource name'() {
    when:
    def pwafModule = new PowerWAFModule("does not exist.json")

    then:
    assert pwafModule.dataSubscriptions.empty
  }

  void 'bad rule data'() {
    when:
    def pwafModule = new PowerWAFModule(getClass().getName().replaceAll('\\.', '/') + '.class')

    then:
    assert pwafModule.dataSubscriptions.empty
  }
}
