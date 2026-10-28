package datadog.trace.api.telemetry;

import static datadog.trace.api.telemetry.ProductChange.ProductType.APPSEC;
import static datadog.trace.api.telemetry.ProductChange.ProductType.PROFILER;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;

class ProductChangeCollectorTest extends DDJavaSpecification {

  @Test
  void updateDrainProductChanges() {
    // setup
    ProductChange product1 = new ProductChange().productType(APPSEC).enabled(true);
    ProductChange product2 = new ProductChange().productType(PROFILER).enabled(false);
    ProductChange product3 = new ProductChange().productType(APPSEC).enabled(false);

    ProductChangeCollector collector = ProductChangeCollector.get();
    collector.update(product1);
    collector.update(product2);

    // when
    collector.update(product3);

    // then
    assertEquals(asList(product1, product2, product3), collector.drain());
    assertEquals(emptyList(), collector.drain());
  }
}
