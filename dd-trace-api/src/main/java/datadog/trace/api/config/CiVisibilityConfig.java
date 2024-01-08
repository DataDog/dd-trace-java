package datadog.trace.api.config;

/** Constant with names of configuration options for CI visibility. */
public final class CiVisibilityConfig {

  public static final String CIVISIBILITY_ENABLED = "civisibility.enabled";
  public static final String CIVISIBILITY_TRACE_SANITATION_ENABLED =
      "civisibility.trace.sanitation.enabled";
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
  public static final String CIVISIBILITY_ADDITIONAL_CHILD_PROCESS_JVM_ARGS =
      "civisibility.additional.child.process.jvm.args";
  public static final String CIVISIBILITY_COMPILER_PLUGIN_AUTO_CONFIGURATION_ENABLED =
      "civisibility.compiler.plugin.auto.configuration.enabled";
  public static final String CIVISIBILITY_COMPILER_PLUGIN_VERSION =
      "civisibility.compiler.plugin.version";
  public static final String CIVISIBILITY_DEBUG_PORT = "civisibility.debug.port";
  public static final String CIVISIBILITY_GIT_UPLOAD_ENABLED = "civisibility.git.upload.enabled";
  public static final String CIVISIBILITY_GIT_UNSHALLOW_ENABLED =
      "civisibility.git.unshallow.enabled";
  public static final String CIVISIBILITY_GIT_UNSHALLOW_DEFER = "civisibility.git.unshallow.defer";
  public static final String CIVISIBILITY_GIT_UPLOAD_TIMEOUT_MILLIS =
      "civisibility.git.upload.timeout.millis";
  public static final String CIVISIBILITY_GIT_COMMAND_TIMEOUT_MILLIS =
      "civisibility.git.command.timeout.millis";
  public static final String CIVISIBILITY_BACKEND_API_TIMEOUT_MILLIS =
      "civisibility.backend.api.timeout.millis";
  public static final String CIVISIBILITY_GIT_REMOTE_NAME = "civisibility.git.remote.name";
  public static final String CIVISIBILITY_SIGNAL_SERVER_HOST = "civisibility.signal.server.host";
  public static final String CIVISIBILITY_SIGNAL_SERVER_PORT = "civisibility.signal.server.port";
  public static final String CIVISIBILITY_ITR_ENABLED = "civisibility.itr.enabled";
  public static final String CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED =
      "civisibility.ciprovider.integration.enabled";
  public static final String CIVISIBILITY_REPO_INDEX_SHARING_ENABLED =
      "civisibility.repo.index.sharing.enabled";
  public static final String CIVISIBILITY_MODULE_EXECUTION_SETTINGS_CACHE_SIZE =
      "civisibility.module.execution.settings.cache.size";
  public static final String CIVISIBILITY_JVM_INFO_CACHE_SIZE = "civisibility.jvm.info.cache.size";
  public static final String CIVISIBILITY_INJECTED_TRACER_VERSION =
      "civisibility.injected.tracer.version";
  public static final String CIVISIBILITY_RESOURCE_FOLDER_NAMES =
      "civisibility.resource.folder.names";
  public static final String CIVISIBILITY_FLAKY_RETRY_ENABLED = "civisibility.flaky.retry.enabled";
  public static final String CIVISIBILITY_FLAKY_RETRY_COUNT = "civisibility.flaky.retry.count";
  public static final String CIVISIBILITY_MODULE_NAME = "civisibility.module.name";

  /* COVERAGE SETTINGS */
  public static final String CIVISIBILITY_CODE_COVERAGE_ENABLED =
      "civisibility.code.coverage.enabled";
  public static final String CIVISIBILITY_CODE_COVERAGE_INCLUDES =
      "civisibility.code.coverage.includes";
  public static final String CIVISIBILITY_CODE_COVERAGE_EXCLUDES =
      "civisibility.code.coverage.excludes";
  public static final String CIVISIBILITY_CODE_COVERAGE_SEGMENTS_ENABLED =
      "civisibility.code.coverage.segments.enabled";
  public static final String CIVISIBILITY_CODE_COVERAGE_ROOT_PACKAGES_LIMIT =
      "civisibility.code.coverage.root.packages.limit";
  public static final String CIVISIBILITY_CODE_COVERAGE_PERCENTAGE_CALCULATION_ENABLED =
      "civisibility.code.coverage.percentage.calculation.enabled";
  public static final String CIVISIBILITY_CODE_COVERAGE_REPORT_DUMP_DIR =
      "civisibility.code.coverage.report.dump.dir";
  public static final String CIVISIBILITY_JACOCO_PLUGIN_VERSION =
      "civisibility.jacoco.plugin.version";
  public static final String CIVISIBILITY_JACOCO_GRADLE_SOURCE_SETS =
      "civisibility.jacoco.gradle.sourcesets";

  private CiVisibilityConfig() {}
}
