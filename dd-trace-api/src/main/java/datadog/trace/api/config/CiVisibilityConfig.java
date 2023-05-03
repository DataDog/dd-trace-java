package datadog.trace.api.config;

/** Constant with names of configuration options for CI visibility. */
public final class CiVisibilityConfig {

  public static final String CIVISIBILITY_ENABLED = "civisibility.enabled";
  public static final String CIVISIBILITY_AGENTLESS_ENABLED = "civisibility.agentless.enabled";
  public static final String CIVISIBILITY_AGENTLESS_URL = "civisibility.agentless.url";
  public static final String CIVISIBILITY_SOURCE_DATA_ENABLED = "civisibility.source.data.enabled";
  public static final String CIVISIBILITY_SESSION_ID = "civisibility.session.id";
  public static final String CIVISIBILITY_MODULE_ID = "civisibility.module.id";
  public static final String CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED =
      "civisibility.build.instrumentation.enabled";
  public static final String CIVISIBILITY_AGENT_JAR_URI = "civisibility.agent.jar.uri";
  public static final String CIVISIBILITY_AUTO_CONFIGURATION_ENABLED =
      "civisibility.auto.configuration.enabled";
  public static final String CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED =
      "civisibility.compiler.plugin.auto.configuration.enabled";
  public static final String CIVISIBILITY_COMPILER_PLUGIN_VERSION =
      "civisibility.compiler.plugin.version";
  public static final String CIVISIBILITY_DEBUG_PORT = "civisibility.debug.port";

  private CiVisibilityConfig() {}
}
