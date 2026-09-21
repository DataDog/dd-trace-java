package datadog.telemetry.products;

import static datadog.trace.api.telemetry.ProductChange.ProductType.APPSEC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.telemetry.TelemetryService;
import datadog.trace.api.telemetry.ProductChange;
import datadog.trace.api.telemetry.ProductChangeCollector;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductChangeActionTest {

  private final ProductChangeAction action = new ProductChangeAction();
  private final TelemetryService telemetryService = mock(TelemetryService.class);

  @Test
  void pushProductChangesIntoTheTelemetryService() {
    ProductChangeCollector.get().update(new ProductChange().productType(APPSEC).enabled(true));

    action.doIteration(telemetryService);

    ArgumentCaptor<ProductChange> productChangeCaptor =
        ArgumentCaptor.forClass(ProductChange.class);
    verify(telemetryService, times(1)).addProductChange(productChangeCaptor.capture());
    ProductChange productChange = productChangeCaptor.getValue();
    assertEquals(APPSEC, productChange.getProductType());
    assertTrue(productChange.isEnabled());

    verifyNoMoreInteractions(telemetryService);
  }
}
