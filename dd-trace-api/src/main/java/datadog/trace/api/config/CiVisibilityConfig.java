package datadog.trace.api.config;

/** Constant with names of configuration options for CI visibility. */
public final class CiVisibilityConfig {

  public static final String CIVISIBILITY_ENABLED = "civisibility.enabled";
  public static final String CIVISIBILITY_AGENTLESS_ENABLED = "civisibility.agentless.enabled";
  public static final String CIVISIBILITY_AGENTLESS_URL = "civisibility.agentless.url";
  public static final String CIVISIBILITY_SOURCE_DATA_ENABLED = "civisibility.source.data.enabled";
  public static final String CIVISIBILITY_SOURCE_DATA_ROOT_CHECK_ENABLED =
      "civisibility.source.data.root.check.enabled";
  public static final String CIVISIBILITY_SESSION_ID = "civisibility.session.id";
  public static final String CIVISIBILITY_MODULE_ID = "civisibility.module.id";
  public static final String CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED =
      "civisibility.build.instrumentation.enabled";
  public static final String CIVISIBILITY_AGENT_JAR_URI = "civisibility.agent.jar.uri";
  public static final String CIVISIBILITY_AUTO_CONFIGURATION_ENABLED =
      "civisibility.auto.configuration.enabled";
  public static final String CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED =
      "civisibility.compiler.plugin.auto.configuration.enabled";
  public static final String CIVISIBILITY_CODE_COVERAGE_ENABLED =
      "civisibility.code.coverage.enabled";
  public static final String CIVISIBILITY_COMPILER_PLUGIN_VERSION =
      "civisibility.compiler.plugin.version";
  public static final String CIVISIBILITY_JACOCO_PLUGIN_VERSION =
      "civisibility.jacoco.plugin.version";
  public static final String CIVISIBILITY_JACOCO_PLUGIN_INCLUDES =
      "civisibility.jacoco.plugin.includes";
  public static final String CIVISIBILITY_JACOCO_PLUGIN_EXCLUDES =
      "civisibility.jacoco.plugin.excludes";
  public static final String CIVISIBILITY_DEBUG_PORT = "civisibility.debug.port";
  public static final String CIVISIBILITY_TEST_EVENTS_HANDLER_CACHE_SIZE =
      "civisibility.test.events.handler.cache.size";
  public static final String CIVISIBILITY_GIT_UPLOAD_ENABLED = "civisibility.git.upload.enabled";
  public static final String CIVISIBILITY_GIT_UPLOAD_TIMEOUT_MILLIS =
      "civisibility.git.upload.timeout.millis";
  public static final String CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS =
      "civisibility.git.command.timeout.millis";
  public static final String CIVISIBILITY_BACKEND_API_TIMEOUT_MILLIS =
      "civisibility.backend.api.timeout.millis";
  public static final String CIVISIBILITY_GIT_REMOTE_NAME = "civisibility.git.remote.name";
  public static final String CIVISIBILITY_ITR_ENABLED = "civisibility.itr.enabled";
  public static final String CIVISIBILITY_SKIPPABLE_TESTS = "civisibility.skippable.tests";

  private CiVisibilityConfig() {}
}
