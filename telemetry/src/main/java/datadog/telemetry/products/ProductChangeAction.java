package datadog.telemetry.products;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.trace.api.telemetry.ProductChange;
import datadog.trace.api.telemetry.ProductChangeCollector;
import java.util.List;

public class ProductChangeAction implements TelemetryRunnable.TelemetryPeriodicAction {

  @Override
  public void doIteration(TelemetryService service) {
    List<ProductChange> productChanges = ProductChangeCollector.get().drain();
    for (ProductChange productChange : productChanges) {
      service.addProductChange(productChange);
    }
  }
}
