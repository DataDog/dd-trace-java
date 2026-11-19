package datadog.trace.api.telemetry;

import static datadog.trace.api.telemetry.ProductChange.ProductType.APPSEC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProductChangeTest {

  @Test
  void testProductChange() {
    // when
    ProductChange productChange = new ProductChange().productType(APPSEC).enabled(true);

    // then
    assertEquals(APPSEC, productChange.getProductType());
    assertTrue(productChange.isEnabled());
  }
}
