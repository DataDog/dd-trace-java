package datadog.trace.bootstrap.instrumentation.ci;

class NoopCIInfo extends CIProviderInfo {

  public static final String NOOP_PROVIDER_NAME = "noop";

  NoopCIInfo() {}

  @Override
  public boolean isCI() {
    return false;
  }
}
