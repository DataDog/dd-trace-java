package datadog.trace.api.config;

/** Constant with names of configuration options for CI visibility. */
public final class CiVisibilityConfig {

  public static final String CIVISIBILITY_ENABLED = "civisibility.enabled";
  public static final String CIVISIBILITY_AGENTLESS_ENABLED = "civisibility.agentless.enabled";
  public static final String CIVISIBILITY_AGENTLESS_URL = "civisibility.agentless.url";
  public static final String CIVISIBILITY_SOURCE_DATA_ENABLED = "civisibility.source.data.enabled";
  public static final String CIVISIBILITY_SESSION_ID = "civisibility.session.id";
  public static final String CIVISIBILITY_MODULE_ID = "civisibility.module.id";

  private CiVisibilityConfig() {}
}
