package datadog.trace.api;

public class DDTags {

  public static final String SPAN_TYPE = "span.type";
  public static final String SERVICE_NAME = "service.name";
  public static final String RESOURCE_NAME = "resource.name";
  public static final String THREAD_NAME = "thread.name";
  public static final String THREAD_ID = "thread.id";
  public static final String DB_STATEMENT = "sql.query";

  public static final String HTTP_QUERY = "http.query.string";
  public static final String HTTP_FRAGMENT = "http.fragment.string";

  public static final String USER_NAME = "user.principal";

  public static final String ERROR_MSG = "error.msg"; // string representing the error message
  public static final String ERROR_TYPE = "error.type"; // string representing the type of the error
  public static final String ERROR_STACK = "error.stack"; // human readable version of the stack

  public static final String TEST_SUITE = "test.suite";
  public static final String TEST_NAME = "test.name";
  public static final String TEST_STATUS = "test.status";
  public static final String TEST_FRAMEWORK = "test.framework";
  public static final String TEST_SKIP_REASON = "test.skip_reason";
  public static final String TEST_TYPE = "test.type";

  public static final String CI_PROVIDER_NAME = "ci.provider.name";
  public static final String CI_PIPELINE_ID = "ci.pipeline.id";
  public static final String CI_PIPELINE_NUMBER = "ci.pipeline.number";
  public static final String CI_PIPELINE_URL = "ci.pipeline.url";
  public static final String CI_JOB_URL = "ci.job.url";
  public static final String CI_WORKSPACE_PATH = "ci.workspace_path";

  public static final String GIT_REPOSITORY_URL = "git.repository_url";
  public static final String GIT_COMMIT_SHA = "git.commit_sha";
  public static final String GIT_BRANCH = "git.branch";
  public static final String GIT_TAG = "git.tag";

  public static final String ANALYTICS_SAMPLE_RATE = "_dd1.sr.eausr";
  @Deprecated public static final String EVENT_SAMPLE_RATE = ANALYTICS_SAMPLE_RATE;

  /** Manually force tracer to be keep the trace */
  public static final String MANUAL_KEEP = "manual.keep";
  /** Manually force tracer to be drop the trace */
  public static final String MANUAL_DROP = "manual.drop";

  public static final String TRACE_START_TIME = "t0";

  /* Tags below are for internal use only. */
  static final String INTERNAL_HOST_NAME = "_dd.hostname";
  public static final String RUNTIME_ID_TAG = "runtime-id";
  static final String SERVICE = "service";
  static final String SERVICE_TAG = SERVICE;
  static final String HOST_TAG = "host";
  public static final String LANGUAGE_TAG_KEY = "language";
  public static final String LANGUAGE_TAG_VALUE = "jvm";
}
