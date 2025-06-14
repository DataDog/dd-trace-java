package datadog.trace.api;

public class DDSpanTypes {
  public static final String HTTP_CLIENT = "http";
  public static final String HTTP_SERVER = "web";
  @Deprecated public static final String WEB_SERVLET = HTTP_SERVER;
  public static final String RPC = "rpc";
  public static final String CACHE = "cache";
  public static final String SOAP = "soap";

  public static final String SQL = "sql";
  public static final String MONGO = "mongodb";
  public static final String CASSANDRA = "cassandra";
  public static final String COUCHBASE = "db"; // Using generic for now.
  public static final String REDIS = "redis";
  public static final String MEMCACHED = "memcached";
  public static final String ELASTICSEARCH = "elasticsearch";
  public static final String OPENSEARCH = "opensearch";
  public static final String HIBERNATE = "hibernate";
  public static final String AEROSPIKE = "aerospike";
  public static final String DATANUCLEUS = "datanucleus";

  public static final String MESSAGE_CLIENT = "queue";
  public static final String MESSAGE_CONSUMER = "queue";
  public static final String MESSAGE_PRODUCER = "queue";
  public static final String MESSAGE_BROKER = "queue";

  public static final String GRAPHQL = "graphql";

  public static final String TEST = "test";
  public static final String TEST_SUITE_END = "test_suite_end";
  public static final String TEST_MODULE_END = "test_module_end";
  public static final String TEST_SESSION_END = "test_session_end";

  public static final String VULNERABILITY = "vulnerability";
  public static final String PROTOBUF = "protobuf";

  public static final String MULE = "mule";
  public static final String VALKEY = "valkey";
  public static final String WEBSOCKET = "websocket";

  public static final String SERVERLESS = "serverless";

  public static final String LLMOBS = "llm";
}
