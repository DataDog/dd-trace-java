package datadog.telemetry.api;

public class Products {

  @com.squareup.moshi.Json(name = "appsec")
  private Product appsec;

  @com.squareup.moshi.Json(name = "profiler")
  private Product profiler;

  @com.squareup.moshi.Json(name = "dynamic_instrumentation")
  private Product dynamicInstrumentation;

  /**
   * Get appsec
   *
   * @return appsec
   */
  public Product getAppsec() {
    return appsec;
  }

  /** Set appsec */
  public void setAppsec(Product appsec) {
    this.appsec = appsec;
  }

  public Products appsec(Product appsec) {
    this.appsec = appsec;
    return this;
  }

  /**
   * Get profiler
   *
   * @return profiler
   */
  public Product getProfiler() {
    return profiler;
  }

  /** Set profiler */
  public void setProfiler(Product profiler) {
    this.profiler = profiler;
  }

  public Products profiler(Product profiler) {
    this.profiler = profiler;
    return this;
  }

  /**
   * Get dynamicInstrumentation
   *
   * @return dynamicInstrumentation
   */
  public Product getDynamicInstrumentation() {
    return dynamicInstrumentation;
  }

  /** Set dynamicInstrumentation */
  public void setDynamicInstrumentation(Product dynamicInstrumentation) {
    this.dynamicInstrumentation = dynamicInstrumentation;
  }

  public Products dynamicInstrumentation(Product dynamicInstrumentation) {
    this.dynamicInstrumentation = dynamicInstrumentation;
    return this;
  }

  /** Create a string representation of this pojo. */
  @Override
  public String toString() {
    final StringBuffer sb = new StringBuffer("Products{");
    sb.append("appsec=").append(appsec);
    sb.append(", profiler=").append(profiler);
    sb.append(", dynamicInstrumentation=").append(dynamicInstrumentation);
    sb.append('}');
    return sb.toString();
  }
}
