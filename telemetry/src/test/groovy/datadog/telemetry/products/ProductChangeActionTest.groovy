package datadog.telemetry.products

import datadog.telemetry.TelemetryService
import datadog.trace.api.telemetry.ProductChange
import datadog.trace.api.telemetry.ProductChangeCollector
import spock.lang.Specification

import static datadog.trace.api.telemetry.ProductChange.ProductType.APPSEC

class ProductChangeActionTest extends Specification {
  ProductChangeAction action = new ProductChangeAction()
  TelemetryService telemetryService = Mock()

  void 'push product changes into the telemetry service'() {
    setup:
    ProductChangeCollector.get().update(new ProductChange().productType(APPSEC).enabled(true ))

    when:
    action.doIteration(telemetryService)

    then:
    1 * telemetryService.addProductChange( { ProductChange product ->
      product.getProductType() == APPSEC &&
        product.isEnabled()
    } )
    0 * _
  }
}
