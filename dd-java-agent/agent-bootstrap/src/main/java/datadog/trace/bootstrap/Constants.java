package datadog.trace.bootstrap;

/**
 * Some useful constants.
 *
 * <p>The idea here is to keep this class safe to inject into client's class loader.
 */
public final class Constants {

  /**
   * packages which will be loaded on the bootstrap classloader
   *
   * <p>Updates should be mirrored in
   * datadog.trace.agent.test.BootstrapClasspathSetupListener#BOOTSTRAP_PACKAGE_PREFIXES_COPY
   */
  public static final String[] BOOTSTRAP_PACKAGE_PREFIXES = {
    "datadog.slf4j",
    "datadog.context",
    "datadog.environment",
    "datadog.json",
    "datadog.yaml",
    "datadog.instrument",
    "datadog.appsec.api",
    "datadog.metrics.api",
    "datadog.metrics.statsd",
    "datadog.trace.api",
    "datadog.trace.bootstrap",
    "datadog.trace.config.inversion",
    "datadog.trace.context",
    "datadog.trace.instrumentation.api",
    "datadog.trace.logging",
    "datadog.trace.util",
  };

  private Constants() {}
}
