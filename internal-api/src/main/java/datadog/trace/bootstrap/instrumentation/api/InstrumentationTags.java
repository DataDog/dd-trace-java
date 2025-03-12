package datadog.trace.bootstrap.instrumentation.api;

public class InstrumentationTags {

  // this exists to make it easy to intern UTF-8 encoding
  // of tag/metric keys used in instrumentations. It should
  // stay reasonably small. TODO when/if this gets large
  // start looking at generating constants based on the
  // enabled instrumentations.

  public static final String PARTITION = "partition";
  public static final String OFFSET = "offset";
  public static final String CONSUMER_GROUP = "kafka.group";
  public static final String PROCESSOR_NAME = "processor.name";
  public static final String RECORD_QUEUE_TIME_MS = "record.queue_time_ms";
  public static final String RECORD_END_TO_END_DURATION_MS = "record.e2e_duration_ms";
  public static final String KAFKA_RECORDS_COUNT = "kafka.records_count";
  public static final String TOMBSTONE = "tombstone";
  public static final String AWS_AGENT = "aws.agent";
  public static final String AWS_SERVICE = "aws.service";
  public static final String TOP_LEVEL_AWS_SERVICE = "aws_service";
  public static final String AWS_OPERATION = "aws.operation";
  public static final String AWS_ENDPOINT = "aws.endpoint";
  public static final String AWS_BUCKET_NAME = "aws.bucket.name";
  public static final String BUCKET_NAME = "bucketname";
  public static final String AWS_OBJECT_KEY = "aws.object.key";
  public static final String AWS_QUEUE_URL = "aws.queue.url";

  public static final String AWS_QUEUE_NAME = "aws.queue.name";
  public static final String QUEUE_NAME = "queuename";
  public static final String AWS_TOPIC_NAME = "aws.topic.name";
  public static final String TOPIC_NAME = "topicname";
  public static final String AWS_STREAM_NAME = "aws.stream.name";
  public static final String STREAM_NAME = "streamname";
  public static final String AWS_TABLE_NAME = "aws.table.name";
  public static final String TABLE_NAME = "tablename";
  public static final String AWS_REQUEST_ID = "aws.requestId";
  public static final String AWS_STORAGE_CLASS = "aws.storage.class";

  // These are temporary keys used for span pointer hash calculation
  public static final String S3_ETAG = "s3.eTag";
  public static final String DYNAMO_PRIMARY_KEY_1 = "dynamodb.primary_key_1";
  public static final String DYNAMO_PRIMARY_KEY_1_VALUE = "dynamodb.primary_key_1_value";
  public static final String DYNAMO_PRIMARY_KEY_2 = "dynamodb.primary_key_2";
  public static final String DYNAMO_PRIMARY_KEY_2_VALUE = "dynamodb.primary_key_2_value";

  public static final String BUCKET = "bucket";
  public static final String CASSANDRA_CONTACT_POINTS = "db.cassandra.contact.points";
  public static final String COUCHBASE_OPERATION_ID = "couchbase.operation_id";
  public static final String COUCHBASE_SEED_NODES = "db.couchbase.seed.nodes";
  public static final String ELASTICSEARCH_REQUEST_INDICES = "elasticsearch.request.indices";
  public static final String ELASTICSEARCH_REQUEST_SEARCH_TYPES =
      "elasticsearch.request.search.types";
  public static final String ELASTICSEARCH_REQUEST_WRITE_TYPE = "elasticsearch.request.write.type";
  public static final String ELASTICSEARCH_REQUEST_WRITE_ROUTING =
      "elasticsearch.request.write.routing";
  public static final String ELASTICSEARCH_REQUEST_WRITE_VERSION =
      "elasticsearch.request.write.version";
  public static final String ELASTICSEARCH_TYPE = "elasticsearch.type";
  public static final String ELASTICSEARCH_ID = "elasticsearch.id";
  public static final String ELASTICSEARCH_VERSION = "elasticsearch.version";
  public static final String ELASTICSEARCH_SHARD_BROADCAST_TOTAL =
      "elasticsearch.shard.broadcast.total";
  public static final String ELASTICSEARCH_SHARD_BROADCAST_SUCCESSFUL =
      "elasticsearch.shard.broadcast.successful";
  public static final String ELASTICSEARCH_SHARD_BROADCAST_FAILED =
      "elasticsearch.shard.broadcast.failed";
  public static final String ELASTICSEARCH_SHARD_BULK_ID = "elasticsearch.shard.bulk.id";
  public static final String ELASTICSEARCH_SHARD_BULK_INDEX = "elasticsearch.shard.bulk.index";
  public static final String ELASTICSEARCH_NODE_FAILURES = "elasticsearch.node.failures";
  public static final String ELASTICSEARCH_NODE_CLUSTER_NAME = "elasticsearch.node.cluster.name";
  public static final String ELASTICSEARCH_SHARD_REPLICATION_TOTAL =
      "elasticsearch.shard.replication.total";
  public static final String ELASTICSEARCH_SHARD_REPLICATION_FAILED =
      "elasticsearch.shard.replication.failed";
  public static final String ELASTICSEARCH_RESPONSE_STATUS = "elasticsearch.response.status";
  public static final String STATUS_CODE = "status.code";
  public static final String STATUS_DESCRIPTION = "status.description";
  public static final String MESSAGE_TYPE = "message.type";
  public static final String MESSAGE_SIZE = "message.size";
  public static final String HYSTRIX_COMMAND = "hystrix.command";
  public static final String HYSTRIX_CIRCUIT_OPEN = "hystrix.circuit-open";
  public static final String HYSTRIX_GROUP = "hystrix.group";
  public static final String SERVLET_CONTEXT = "servlet.context";
  public static final String SERVLET_PATH = "servlet.path";
  public static final String SERVLET_DISPATCH = "servlet.dispatch";
  public static final String TIMEOUT = "timeout";
  public static final String JMS_PRODUCE = "jms.produce";
  public static final String JSP_COMPILER = "jsp.compiler";
  public static final String JSP_CLASSFQCN = "jsp.classFQCN";
  public static final String JSP_FORWARD_ORIGIN = "jsp.forwardOrigin";
  public static final String DB_REDIS_DBINDEX = "db.redis.dbIndex";
  public static final String DB_COMMAND_CANCELLED = "db.command.cancelled";
  public static final String DB_COMMAND_RESULTS_COUNT = "db.command.results.count";
  public static final String AMQP_DELIVERY_MODE = "amqp.delivery_mode";
  public static final String AMQP_COMMAND = "amqp.command";
  public static final String AMQP_EXCHANGE = "amqp.exchange";
  public static final String AMQP_ROUTING_KEY = "amqp.routing_key";
  public static final String AMQP_QUEUE = "amqp.queue";
  public static final String EVENT = "event";
  public static final String MESSAGE = "message";
  public static final String HANDLER_TYPE = "handler.type";
  public static final String KAFKA_BOOTSTRAP_SERVERS = "messaging.kafka.bootstrap.servers";
  public static final String MESSAGING_DESTINATION_NAME = "messaging.destination.name";
  public static final String QUARTZ_JOB_NAME = "quartz.job.name";
  public static final String QUARTZ_JOB_GROUP = "quartz.job.group";
  public static final String QUARTZ_TRIGGER_NAME = "quartz.trigger.name";
  public static final String QUARTZ_TRIGGER_GROUP = "quartz.trigger.group";
  public static final String REQUEST_PREDICATE = "request.predicate";
  public static final String VIEW_NAME = "view.name";
  public static final String VIEW_TYPE = "view.type";
  public static final String TWILIO_TYPE = "twilio.type";
  public static final String TWILIO_ACCOUNT = "twilio.account";
  public static final String TWILIO_SID = "twilio.sid";
  public static final String TWILIO_STATUS = "twilio.status";
  public static final String TWILIO_PARENT_SID = "twilio.parentSid";
  public static final String DBM_TRACE_INJECTED = "_dd.dbm_trace_injected";
  public static final String INSTRUMENTATION_TIME_MS = "_dd.instrumentation.time_ms";
  public static final UTF8BytesString DD_MEASURED = UTF8BytesString.create("_dd.measured");
  public static final UTF8BytesString DD_TOP_LEVEL = UTF8BytesString.create("_dd.top_level");
  public static final UTF8BytesString DD_PARTIAL_VERSION =
      UTF8BytesString.create("_dd.partial_version");
  public static final UTF8BytesString DD_WAS_LONG_RUNNING =
      UTF8BytesString.create("_dd.was_long_running");

  public static final String TIBCO_NODE = "tibco.node";
  public static final String TIBCO_VERSION = "tibco.version";

  public static final String MULE_CORRELATION_ID = "mule.correlation_id";
  public static final String MULE_LOCATION = "mule.location";

  public static final String WEBSOCKET_SESSION_ID = "websocket.session.id";
  public static final String WEBSOCKET_MESSAGE_TYPE = "websocket.message.type";
  public static final String WEBSOCKET_MESSAGE_LENGTH = "websocket.message.length";
  public static final String WEBSOCKET_MESSAGE_FRAMES = "websocket.message.frames";
  public static final String WEBSOCKET_MESSAGE_RECEIVE_TIME = "websocket.message.receive_time";
  public static final String WEBSOCKET_CLOSE_CODE = "websocket.close.code";
  public static final String WEBSOCKET_CLOSE_REASON = "websocket.close.reason";
}
