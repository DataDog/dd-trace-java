package datadog.telemetry.products;

import datadog.telemetry.TelemetryRunnable;
import datadog.telemetry.TelemetryService;
import datadog.trace.api.telemetry.Product;
import datadog.trace.api.telemetry.ProductChangeCollector;
import java.util.List;

public class ProductChangeAction implements TelemetryRunnable.TelemetryPeriodicAction {

  @Override
  public void doIteration(TelemetryService service) {
    List<Product> productChanges = ProductChangeCollector.get().drain();
    productChanges.forEach(service::addProductChange);
  }
}
