package datadog.trace.api.telemetry

import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.telemetry.ProductChange.ProductType.APPSEC

class ProductChangeTest extends DDSpecification {

  def "Test ProductChange"() {
    when:
    def productChange = new ProductChange().productType(APPSEC).enabled(true)

    then:
    productChange.getProductType() == APPSEC
    productChange.isEnabled()
  }
}
