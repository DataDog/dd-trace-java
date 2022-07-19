package datadog.trace.bootstrap.instrumentation.api;

// standard tag names (and span kind values) from OpenTracing (see io.opentracing.tag.Tags)
public class Tags {

  public static final String SPAN_KIND_SERVER = "server";
  public static final String SPAN_KIND_CLIENT = "client";
  public static final String SPAN_KIND_PRODUCER = "producer";
  public static final String SPAN_KIND_CONSUMER = "consumer";
  public static final String SPAN_KIND_BROKER = "broker";
  public static final String SPAN_KIND_TEST = "test";

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
  public static final String PEER_HOST_IPV4 = "peer.ipv4";
  public static final String PEER_HOST_IPV6 = "peer.ipv6";
  public static final String PEER_SERVICE = "peer.service";
  public static final String PEER_HOSTNAME = "peer.hostname";
  public static final String PEER_PORT = "peer.port";
  public static final String SAMPLING_PRIORITY = "sampling.priority";
  public static final String SPAN_KIND = "span.kind";
  public static final String COMPONENT = "component";
  public static final String ERROR = "error";
  public static final String DB_TYPE = "db.type";
  public static final String DB_INSTANCE = "db.instance";
  public static final String DB_USER = "db.user";
  public static final String DB_OPERATION = "db.operation";
  public static final String DB_STATEMENT = "db.statement";
  public static final String MESSAGE_BUS_DESTINATION = "message_bus.destination";

  public static final String TEST_SUITE = "test.suite";
  public static final String TEST_NAME = "test.name";
  public static final String TEST_STATUS = "test.status";
  public static final String TEST_FRAMEWORK = "test.framework";
  public static final String TEST_FRAMEWORK_VERSION = "test.framework_version";
  public static final String TEST_SKIP_REASON = "test.skip_reason";
  public static final String TEST_TYPE = "test.type";
  public static final String TEST_PARAMETERS = "test.parameters";

  public static final String CI_PROVIDER_NAME = "ci.provider.name";
  public static final String CI_PIPELINE_ID = "ci.pipeline.id";
  public static final String CI_PIPELINE_NAME = "ci.pipeline.name";
  public static final String CI_PIPELINE_NUMBER = "ci.pipeline.number";
  public static final String CI_PIPELINE_URL = "ci.pipeline.url";
  public static final String CI_STAGE_NAME = "ci.stage.name";
  public static final String CI_JOB_NAME = "ci.job.name";
  public static final String CI_JOB_URL = "ci.job.url";
  public static final String CI_WORKSPACE_PATH = "ci.workspace_path";

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

  public static final String RUNTIME_NAME = "runtime.name";
  public static final String RUNTIME_VENDOR = "runtime.vendor";
  public static final String RUNTIME_VERSION = "runtime.version";
  public static final String OS_ARCHITECTURE = "os.architecture";
  public static final String OS_PLATFORM = "os.platform";
  public static final String OS_VERSION = "os.version";

  public static final String DD_SERVICE = "dd.service";
  public static final String DD_VERSION = "dd.version";
  public static final String DD_ENV = "dd.env";
}
