package datadog.trace.api.telemetry

import datadog.trace.test.util.DDSpecification

import static datadog.trace.api.telemetry.ProductChange.ProductType.APPSEC
import static datadog.trace.api.telemetry.ProductChange.ProductType.PROFILER

class ProductChangeCollectorTest extends DDSpecification {

  def "update-drain product changes"() {
    setup:
    def product1 = new ProductChange().productType(APPSEC).enabled(true)
    def product2 = new ProductChange().productType(PROFILER).enabled(false)
    def product3 = new ProductChange().productType(APPSEC).enabled(false)

    ProductChangeCollector.get().productChanges.offer(product1)
    ProductChangeCollector.get().productChanges.offer(product2)

    when:
    ProductChangeCollector.get().update(product3)

    then:
    ProductChangeCollector.get().drain() == [product1, product2, product3]
    ProductChangeCollector.get().drain() == []
  }
}
