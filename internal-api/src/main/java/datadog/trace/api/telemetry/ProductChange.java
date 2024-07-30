package datadog.trace.api.telemetry;

public class ProductChange {

  private ProductType productType;
  private boolean enabled;

  public ProductType getProductType() {
    return productType;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public ProductChange productType(ProductType productType) {
    this.productType = productType;
    return this;
  }

  public ProductChange enabled(boolean enabled) {
    this.enabled = enabled;
    return this;
  }

  public enum ProductType {
    APPSEC("appsec"),
    PROFILER("profiler"),
    DYNAMIC_INSTRUMENTATION("dynamic_instrumentation");

    private final String name;

    ProductType(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }
  }
}
