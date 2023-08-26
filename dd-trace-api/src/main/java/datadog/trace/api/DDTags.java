package datadog.trace.api;

public class DDTags {

  public static final String SPAN_TYPE = "span.type";
  public static final String SERVICE_NAME = "service.name";
  public static final String RESOURCE_NAME = "resource.name";
  public static final String THREAD_NAME = "thread.name";
  public static final String THREAD_ID = "thread.id";
  public static final String DB_STATEMENT = "sql.query";
  public static final String PATHWAY_HASH = "pathway.hash";

  public static final String HTTP_QUERY = "http.query.string";
  public static final String HTTP_FRAGMENT = "http.fragment.string";

  public static final String USER_NAME = "user.principal";

  public static final String ERROR_MSG = "error.message"; // string representing the error message
  public static final String ERROR_TYPE = "error.type"; // string representing the type of the error
  public static final String ERROR_STACK = "error.stack"; // human-readable version of the stack

  public static final String ANALYTICS_SAMPLE_RATE = "_dd1.sr.eausr";
  @Deprecated public static final String EVENT_SAMPLE_RATE = ANALYTICS_SAMPLE_RATE;

  /** Manually force tracer to keep the trace */
  public static final String MANUAL_KEEP = "manual.keep";
  /** Manually force tracer to drop the trace */
  public static final String MANUAL_DROP = "manual.drop";

  public static final String TRACE_START_TIME = "t0";

  /* Tags below are for internal use only. */
  static final String INTERNAL_HOST_NAME = "_dd.hostname";
  public static final String RUNTIME_ID_TAG = "runtime-id";
  public static final String RUNTIME_VERSION_TAG = "runtime_version";
  static final String SERVICE = "service";
  static final String SERVICE_TAG = SERVICE;
  static final String HOST_TAG = "host";
  public static final String LANGUAGE_TAG_KEY = "language";
  public static final String LANGUAGE_TAG_VALUE = "jvm";
  public static final String ORIGIN_KEY = "_dd.origin";
  public static final String SPAN_LINKS = "_dd.span_links";
  public static final String LIBRARY_VERSION_TAG_KEY = "library_version";
  public static final String CI_ENV_VARS = "_dd.ci.env_vars";
  public static final String CI_ITR_TESTS_SKIPPED = "_dd.ci.itr.tests_skipped";
  public static final String MEASURED = "_dd.measured";
  public static final String PID_TAG = "process_id";
  public static final String SCHEMA_VERSION_TAG_KEY = "_dd.trace_span_attribute_schema";
  public static final String PEER_SERVICE_SOURCE = "_dd.peer.service.source";
  public static final String PEER_SERVICE_REMAPPED_FROM = "_dd.peer.service.remapped_from";
  public static final String INTERNAL_GIT_REPOSITORY_URL = "_dd.git.repository_url";
  public static final String INTERNAL_GIT_COMMIT_SHA = "_dd.git.commit.sha";

  public static final String PROFILING_ENABLED = "_dd.profiling.enabled";
  public static final String BASE_SERVICE = "_dd.base_service";
}
