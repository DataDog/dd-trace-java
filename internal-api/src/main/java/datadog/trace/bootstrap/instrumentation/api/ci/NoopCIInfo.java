package datadog.trace.bootstrap.instrumentation.api.ci;

class NoopCIInfo extends CIProviderInfo {

  public static final String NOOP_PROVIDER_NAME = "noop";

  @Override
  public boolean isCI() {
    return false;
  }
}
