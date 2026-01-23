package datadog.trace.bootstrap.instrumentation.api;

// standard tag names (and span kind values) from OpenTracing (see io.opentracing.tag.Tags)
public class Tags {

  public static final String SPAN_KIND_SERVER = "server";
  public static final String SPAN_KIND_CLIENT = "client";
  public static final String SPAN_KIND_PRODUCER = "producer";
  public static final String SPAN_KIND_CONSUMER = "consumer";
  public static final String SPAN_KIND_BROKER = "broker";
  public static final String SPAN_KIND_TEST = "test";
  public static final String SPAN_KIND_TEST_SUITE = "test_suite_end";
  public static final String SPAN_KIND_TEST_MODULE = "test_module_end";
  public static final String SPAN_KIND_TEST_SESSION = "test_session_end";
  public static final String SPAN_KIND_INTERNAL = "internal";

  public static final String HTTP_URL = "http.url";
  public static final String HTTP_HOSTNAME = "http.hostname";
  public static final String HTTP_ROUTE = "http.route";
  public static final String HTTP_STATUS = "http.status_code";
  public static final String HTTP_METHOD = "http.method";
  public static final String HTTP_FORWARDED = "http.forwarded";
  public static final String HTTP_FORWARDED_PROTO = "http.forwarded.proto";
  public static final String HTTP_FORWARDED_HOST = "http.forwarded.host";
  public static final String HTTP_FORWARDED_IP = "http.forwarded.ip";
  public static final String HTTP_FORWARDED_PORT = "http.forwarded.port";
  public static final String HTTP_USER_AGENT = "http.useragent";
  public static final String HTTP_CLIENT_IP = "http.client_ip";
  public static final String HTTP_REQUEST_CONTENT_LENGTH = "http.request_content_length";
  public static final String HTTP_RESPONSE_CONTENT_LENGTH = "http.response_content_length";
  public static final String PEER_HOST_IPV4 = "peer.ipv4";
  public static final String PEER_HOST_IPV6 = "peer.ipv6";
  public static final String PEER_SERVICE = "peer.service";
  public static final String PEER_HOSTNAME = "peer.hostname";
  public static final String PEER_PORT = "peer.port";
  public static final String RPC_SERVICE = "rpc.service";
  public static final String SAMPLING_PRIORITY = "sampling.priority";
  public static final String SPAN_KIND = "span.kind";
  public static final String COMPONENT = "component";
  public static final String ERROR = "error";
  public static final String DB_TYPE = "db.type";
  public static final String DB_INSTANCE = "db.instance";
  public static final String DB_USER = "db.user";
  public static final String DB_OPERATION = "db.operation";
  public static final String DB_STATEMENT = "db.statement";
  public static final String DB_WAREHOUSE = "db.warehouse";
  public static final String DB_HOST = "db.host";
  public static final String DB_SCHEMA = "db.schema";
  public static final String MESSAGE_BUS_DESTINATION = "message_bus.destination";
  public static final String DB_POOL_NAME = "db.pool.name";

  public static final String TEST_SESSION_NAME = "test_session.name";
  public static final String TEST_MODULE = "test.module";
  public static final String TEST_SUITE = "test.suite";
  public static final String TEST_NAME = "test.name";
  public static final String TEST_STATUS = "test.status";
  public static final String TEST_FRAMEWORK = "test.framework";
  public static final String TEST_FRAMEWORK_VERSION = "test.framework_version";
  public static final String TEST_SKIP_REASON = "test.skip_reason";
  public static final String TEST_SKIPPED_BY_ITR = "test.skipped_by_itr";
  public static final String TEST_TYPE = "test.type";
  public static final String TEST_PARAMETERS = "test.parameters";
  public static final String TEST_CODEOWNERS = "test.codeowners";
  public static final String TEST_SOURCE_FILE = "test.source.file";
  public static final String TEST_SOURCE_CLASS = "test.source.class";
  public static final String TEST_SOURCE_METHOD = "test.source.method";
  public static final String TEST_SOURCE_START = "test.source.start";
  public static final String TEST_SOURCE_END = "test.source.end";
  public static final String TEST_TRAITS = "test.traits";
  public static final String TEST_COMMAND = "test.command";
  public static final String TEST_TOOLCHAIN = "test.toolchain";
  public static final String TEST_EXECUTION = "test.execution";
  public static final String TEST_GRADLE_NESTED_BUILD = "test.gradle.nested_build";
  public static final String TEST_IS_RUM_ACTIVE = "test.is_rum_active";
  public static final String TEST_BROWSER_DRIVER = "test.browser.driver";
  public static final String TEST_BROWSER_DRIVER_VERSION = "test.browser.driver_version";
  public static final String TEST_BROWSER_NAME = "test.browser.name";
  public static final String TEST_BROWSER_VERSION = "test.browser.version";
  public static final String TEST_CALLBACK = "test.callback";

  public static final String TEST_SESSION_ID = "test_session_id";
  public static final String TEST_MODULE_ID = "test_module_id";
  public static final String TEST_SUITE_ID = "test_suite_id";
  public static final String ITR_CORRELATION_ID = "itr_correlation_id";
  public static final String TEST_CODE_COVERAGE_ENABLED = "test.code_coverage.enabled";
  public static final String TEST_CODE_COVERAGE_LINES_PERCENTAGE = "test.code_coverage.lines_pct";
  public static final String TEST_CODE_COVERAGE_BACKFILLED = "test.code_coverage.backfilled";
  public static final String TEST_ITR_TESTS_SKIPPING_ENABLED = "test.itr.tests_skipping.enabled";
  public static final String TEST_ITR_TESTS_SKIPPING_TYPE = "test.itr.tests_skipping.type";
  public static final String TEST_ITR_TESTS_SKIPPING_COUNT = "test.itr.tests_skipping.count";
  public static final String TEST_ITR_UNSKIPPABLE = "test.itr.unskippable";
  public static final String TEST_ITR_FORCED_RUN = "test.itr.forced_run";
  public static final String TEST_EARLY_FLAKE_ENABLED = "test.early_flake.enabled";
  public static final String TEST_EARLY_FLAKE_ABORT_REASON = "test.early_flake.abort_reason";
  public static final String TEST_IS_NEW = "test.is_new";
  public static final String TEST_IS_RETRY = "test.is_retry";
  public static final String TEST_RETRY_REASON = "test.retry_reason";
  public static final String TEST_IS_MODIFIED = "test.is_modified";
  public static final String TEST_HAS_FAILED_ALL_RETRIES = "test.has_failed_all_retries";
  public static final String TEST_FAILURE_SUPPRESSED = "test.failure_suppressed";
  public static final String TEST_TEST_MANAGEMENT_ENABLED = "test.test_management.enabled";
  public static final String TEST_TEST_MANAGEMENT_IS_QUARANTINED =
      "test.test_management.is_quarantined";
  public static final String TEST_TEST_MANAGEMENT_IS_TEST_DISABLED =
      "test.test_management.is_test_disabled";
  public static final String TEST_TEST_MANAGEMENT_IS_ATTEMPT_TO_FIX =
      "test.test_management.is_attempt_to_fix";
  public static final String TEST_TEST_MANAGEMENT_ATTEMPT_TO_FIX_PASSED =
      "test.test_management.attempt_to_fix_passed";

  public static final String ERROR_DEBUG_INFO_CAPTURED = "error.debug_info_captured";

  public static final String CI_PROVIDER_NAME = "ci.provider.name";
  public static final String CI_PIPELINE_ID = "ci.pipeline.id";
  public static final String CI_PIPELINE_NAME = "ci.pipeline.name";
  public static final String CI_PIPELINE_NUMBER = "ci.pipeline.number";
  public static final String CI_PIPELINE_URL = "ci.pipeline.url";
  public static final String CI_STAGE_NAME = "ci.stage.name";
  public static final String CI_JOB_ID = "ci.job.id";
  public static final String CI_JOB_NAME = "ci.job.name";
  public static final String CI_JOB_URL = "ci.job.url";
  public static final String CI_WORKSPACE_PATH = "ci.workspace_path";
  public static final String CI_NODE_NAME = "ci.node.name";
  public static final String CI_NODE_LABELS = "ci.node.labels";

  public static final String GIT_REPOSITORY_URL = "git.repository_url";
  public static final String GIT_COMMIT_SHA = "git.commit.sha";
  public static final String GIT_COMMIT_AUTHOR_NAME = "git.commit.author.name";
  public static final String GIT_COMMIT_AUTHOR_EMAIL = "git.commit.author.email";
  public static final String GIT_COMMIT_AUTHOR_DATE = "git.commit.author.date";
  public static final String GIT_COMMIT_COMMITTER_NAME = "git.commit.committer.name";
  public static final String GIT_COMMIT_COMMITTER_EMAIL = "git.commit.committer.email";
  public static final String GIT_COMMIT_COMMITTER_DATE = "git.commit.committer.date";
  public static final String GIT_COMMIT_MESSAGE = "git.commit.message";
  public static final String GIT_BRANCH = "git.branch";
  public static final String GIT_TAG = "git.tag";
  public static final String GIT_PULL_REQUEST_BASE_BRANCH = "git.pull_request.base_branch";
  public static final String GIT_PULL_REQUEST_BASE_BRANCH_SHA = "git.pull_request.base_branch_sha";
  public static final String GIT_PULL_REQUEST_BASE_BRANCH_HEAD_SHA =
      "git.pull_request.base_branch_head_sha";
  public static final String GIT_COMMIT_HEAD_SHA = "git.commit.head.sha";
  public static final String GIT_COMMIT_HEAD_AUTHOR_NAME = "git.commit.head.author.name";
  public static final String GIT_COMMIT_HEAD_AUTHOR_EMAIL = "git.commit.head.author.email";
  public static final String GIT_COMMIT_HEAD_AUTHOR_DATE = "git.commit.head.author.date";
  public static final String GIT_COMMIT_HEAD_COMMITTER_NAME = "git.commit.head.committer.name";
  public static final String GIT_COMMIT_HEAD_COMMITTER_EMAIL = "git.commit.head.committer.email";
  public static final String GIT_COMMIT_HEAD_COMMITTER_DATE = "git.commit.head.committer.date";
  public static final String GIT_COMMIT_HEAD_MESSAGE = "git.commit.head.message";
  public static final String PULL_REQUEST_NUMBER = "pr.number";

  public static final String RUNTIME_NAME = "runtime.name";
  public static final String RUNTIME_VENDOR = "runtime.vendor";
  public static final String RUNTIME_VERSION = "runtime.version";
  public static final String OS_ARCHITECTURE = "os.architecture";
  public static final String OS_PLATFORM = "os.platform";
  public static final String OS_VERSION = "os.version";

  public static final String DD_SERVICE = "dd.service";
  public static final String DD_VERSION = "dd.version";
  public static final String DD_ENV = "dd.env";

  public static final String ENV = "env";

  /** ASM force tracer to keep the trace */
  public static final String ASM_KEEP = "asm.keep";

  public static final String PROPAGATED_TRACE_SOURCE = "_dd.p.ts";
  public static final String PROPAGATED_DEBUG = "_dd.p.debug";

  public static final String LLMOBS_LLM_SPAN_KIND = "llm";
  public static final String LLMOBS_WORKFLOW_SPAN_KIND = "workflow";
  public static final String LLMOBS_TASK_SPAN_KIND = "task";
  public static final String LLMOBS_AGENT_SPAN_KIND = "agent";
  public static final String LLMOBS_TOOL_SPAN_KIND = "tool";
  public static final String LLMOBS_EMBEDDING_SPAN_KIND = "embedding";
  public static final String LLMOBS_RETRIEVAL_SPAN_KIND = "retrieval";
  public static final String DSM_TRANSACTION_ID = "dsm.transaction.id";
  public static final String DSM_TRANSACTION_CHECKPOINT = "dsm.transaction.checkpoint";
}
