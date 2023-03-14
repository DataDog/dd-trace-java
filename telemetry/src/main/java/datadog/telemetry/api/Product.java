package datadog.telemetry.api;

public class Product {

  @com.squareup.moshi.Json(name = "version")
  private String version;

  @com.squareup.moshi.Json(name = "enabled")
  private Boolean enabled;

  @com.squareup.moshi.Json(name = "error")
  private ProductError error;

  /**
   * Get version
   *
   * @return version
   */
  public String getVersion() {
    return version;
  }

  /** Set version */
  public void setVersion(String version) {
    this.version = version;
  }

  public Product version(String version) {
    this.version = version;
    return this;
  }

  /**
   * Get enabled
   *
   * @return enabled
   */
  public Boolean getEnabled() {
    return enabled;
  }

  /** Set enabled */
  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  public Product enabled(Boolean enabled) {
    this.enabled = enabled;
    return this;
  }

  /**
   * Get error
   *
   * @return error
   */
  public ProductError getError() {
    return error;
  }

  /** Set error */
  public void setError(ProductError error) {
    this.error = error;
  }

  public Product error(ProductError error) {
    this.error = error;
    return this;
  }

  /** Create a string representation of this pojo. */
  @Override
  public String toString() {
    final StringBuffer sb = new StringBuffer("Product{");
    sb.append("version='").append(version).append('\'');
    sb.append(", enabled=").append(enabled);
    sb.append(", error=").append(error);
    sb.append('}');
    return sb.toString();
  }
}
