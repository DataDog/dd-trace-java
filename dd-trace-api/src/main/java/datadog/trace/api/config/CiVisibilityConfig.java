package datadog.trace.api.config;

/** Constant with names of configuration options for CI visibility. */
public final class CiVisibilityConfig {

  public static final String CIVISIBILITY_ENABLED = "civisibility.enabled";
  public static final String CIVISIBILITY_TRACE_SANITATION_ENABLED =
      "civisibility.trace.sanitation.enabled";
  public static final String CIVISIBILITY_AGENTLESS_ENABLED = "civisibility.agentless.enabled";
  public static final String CIVISIBILITY_AGENTLESS_URL = "civisibility.agentless.url";
  public static final String CIVISIBILITY_INTAKE_AGENTLESS_URL =
      "civisibility.intake.agentless.url";
  public static final String CIVISIBILITY_SOURCE_DATA_ENABLED = "civisibility.source.data.enabled";
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
  public static final String CIVISIBILITY_GIT_CLIENT_ENABLED = "civisibility.git.client.enabled";
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
  public static final String CIVISIBILITY_SIGNAL_CLIENT_TIMEOUT_MILLIS =
      "civisibility.signal.client.timeout.millis";
  public static final String CIVISIBILITY_ITR_ENABLED = "civisibility.itr.enabled";
  public static final String CIVISIBILITY_TEST_SKIPPING_ENABLED =
      "civisibility.test.skipping.enabled";
  public static final String CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED =
      "civisibility.ciprovider.integration.enabled";
  public static final String CIVISIBILITY_REPO_INDEX_DUPLICATE_KEY_CHECK_ENABLED =
      "civisibility.repo.index.duplicate.key.check.enabled";
  public static final String CIVISIBILITY_REPO_INDEX_FOLLOW_SYMLINKS =
      "civisibility.repo.index.follow.symlinks";
  public static final String CIVISIBILITY_EXECUTION_SETTINGS_CACHE_SIZE =
      "civisibility.execution.settings.cache.size";
  public static final String CIVISIBILITY_JVM_INFO_CACHE_SIZE = "civisibility.jvm.info.cache.size";
  public static final String CIVISIBILITY_INJECTED_TRACER_VERSION =
      "civisibility.injected.tracer.version";
  public static final String CIVISIBILITY_RESOURCE_FOLDER_NAMES =
      "civisibility.resource.folder.names";
  public static final String CIVISIBILITY_FLAKY_RETRY_ENABLED = "civisibility.flaky.retry.enabled";
  public static final String CIVISIBILITY_IMPACTED_TESTS_DETECTION_ENABLED =
      "civisibility.impacted.tests.detection.enabled";
  public static final String CIVISIBILITY_KNOWN_TESTS_REQUEST_ENABLED =
      "civisibility.known.tests.request.enabled";
  public static final String CIVISIBILITY_FLAKY_RETRY_ONLY_KNOWN_FLAKES =
      "civisibility.flaky.retry.only.known.flakes";
  public static final String CIVISIBILITY_EARLY_FLAKE_DETECTION_ENABLED =
      "civisibility.early.flake.detection.enabled";
  public static final String CIVISIBILITY_EARLY_FLAKE_DETECTION_LOWER_LIMIT =
      "civisibility.early.flake.detection.lower.limit";
  public static final String CIVISIBILITY_FLAKY_RETRY_COUNT = "civisibility.flaky.retry.count";
  public static final String CIVISIBILITY_TOTAL_FLAKY_RETRY_COUNT =
      "civisibility.total.flaky.retry.count";
  public static final String CIVISIBILITY_MODULE_NAME = "civisibility.module.name";
  public static final String CIVISIBILITY_TEST_COMMAND = "civisibility.test.command";
  public static final String CIVISIBILITY_TELEMETRY_ENABLED = "civisibility.telemetry.enabled";
  public static final String CIVISIBILITY_RUM_FLUSH_WAIT_MILLIS =
      "civisibility.rum.flush.wait.millis";
  public static final String CIVISIBILITY_AUTO_INSTRUMENTATION_PROVIDER =
      "civisibility.auto.instrumentation.provider";
  public static final String CIVISIBILITY_TEST_ORDER = "civisibility.test.order";
  public static final String CIVISIBILITY_SCALATEST_FORK_MONITOR_ENABLED =
      "civisibility.scalatest.fork.monitor.enabled";
  public static final String TEST_MANAGEMENT_ENABLED = "test.management.enabled";
  public static final String TEST_MANAGEMENT_ATTEMPT_TO_FIX_RETRIES =
      "test.management.attempt.to.fix.retries";
  public static final String TEST_FAILED_TEST_REPLAY_ENABLED = "test.failed.test.replay.enabled";

  /* Git PR info */
  public static final String GIT_PULL_REQUEST_BASE_BRANCH = "git.pull.request.base.branch";
  public static final String GIT_PULL_REQUEST_BASE_BRANCH_SHA = "git.pull.request.base.branch.sha";
  public static final String GIT_COMMIT_HEAD_SHA = "git.commit.head.sha";

  /* COVERAGE SETTINGS */
  public static final String CIVISIBILITY_CODE_COVERAGE_ENABLED =
      "civisibility.code.coverage.enabled";
  public static final String CIVISIBILITY_CODE_COVERAGE_LINES_ENABLED =
      "civisibility.code.coverage.lines.enabled";
  public static final String CIVISIBILITY_CODE_COVERAGE_INCLUDES =
      "civisibility.code.coverage.includes";
  public static final String CIVISIBILITY_CODE_COVERAGE_EXCLUDES =
      "civisibility.code.coverage.excludes";
  public static final String CIVISIBILITY_CODE_COVERAGE_ROOT_PACKAGES_LIMIT =
      "civisibility.code.coverage.root.packages.limit";
  public static final String CIVISIBILITY_CODE_COVERAGE_REPORT_DUMP_DIR =
      "civisibility.code.coverage.report.dump.dir";
  public static final String CIVISIBILITY_JACOCO_PLUGIN_VERSION =
      "civisibility.jacoco.plugin.version";
  public static final String CIVISIBILITY_GRADLE_SOURCE_SETS = "civisibility.gradle.sourcesets";
  public static final String CIVISIBILITY_CODE_COVERAGE_REPORT_UPLOAD_ENABLED =
      "civisibility.code.coverage.report.upload.enabled";

  public static final String TEST_SESSION_NAME = "test.session.name";

  private CiVisibilityConfig() {}
}
