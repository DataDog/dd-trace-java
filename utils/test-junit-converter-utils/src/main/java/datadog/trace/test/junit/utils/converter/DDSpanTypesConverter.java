package datadog.trace.test.junit.utils.converter;

import static datadog.trace.api.DDSpanTypes.AEROSPIKE;
import static datadog.trace.api.DDSpanTypes.CACHE;
import static datadog.trace.api.DDSpanTypes.CASSANDRA;
import static datadog.trace.api.DDSpanTypes.COUCHBASE;
import static datadog.trace.api.DDSpanTypes.DATANUCLEUS;
import static datadog.trace.api.DDSpanTypes.ELASTICSEARCH;
import static datadog.trace.api.DDSpanTypes.GRAPHQL;
import static datadog.trace.api.DDSpanTypes.HIBERNATE;
import static datadog.trace.api.DDSpanTypes.HTTP_CLIENT;
import static datadog.trace.api.DDSpanTypes.HTTP_SERVER;
import static datadog.trace.api.DDSpanTypes.LLMOBS;
import static datadog.trace.api.DDSpanTypes.MEMCACHED;
import static datadog.trace.api.DDSpanTypes.MESSAGE_BROKER;
import static datadog.trace.api.DDSpanTypes.MESSAGE_CLIENT;
import static datadog.trace.api.DDSpanTypes.MESSAGE_CONSUMER;
import static datadog.trace.api.DDSpanTypes.MESSAGE_PRODUCER;
import static datadog.trace.api.DDSpanTypes.MONGO;
import static datadog.trace.api.DDSpanTypes.MULE;
import static datadog.trace.api.DDSpanTypes.OPENSEARCH;
import static datadog.trace.api.DDSpanTypes.PROTOBUF;
import static datadog.trace.api.DDSpanTypes.REDIS;
import static datadog.trace.api.DDSpanTypes.RPC;
import static datadog.trace.api.DDSpanTypes.SERVERLESS;
import static datadog.trace.api.DDSpanTypes.SOAP;
import static datadog.trace.api.DDSpanTypes.SQL;
import static datadog.trace.api.DDSpanTypes.TEST;
import static datadog.trace.api.DDSpanTypes.TEST_MODULE_END;
import static datadog.trace.api.DDSpanTypes.TEST_SESSION_END;
import static datadog.trace.api.DDSpanTypes.TEST_SUITE_END;
import static datadog.trace.api.DDSpanTypes.VALKEY;
import static datadog.trace.api.DDSpanTypes.VULNERABILITY;
import static datadog.trace.api.DDSpanTypes.WEBSOCKET;

import java.util.HashMap;
import java.util.Map;

public class DDSpanTypesConverter extends AbstractClassConstantConvertor<String> {
  private static final Map<String, String> MAPPING;

  static {
    MAPPING = new HashMap<>();
    MAPPING.put("HTTP_CLIENT", HTTP_CLIENT);
    MAPPING.put("HTTP_SERVER", HTTP_SERVER);
    MAPPING.put("RPC", RPC);
    MAPPING.put("CACHE", CACHE);
    MAPPING.put("SOAP", SOAP);
    MAPPING.put("SQL", SQL);
    MAPPING.put("MONGO", MONGO);
    MAPPING.put("CASSANDRA", CASSANDRA);
    MAPPING.put("COUCHBASE", COUCHBASE);
    MAPPING.put("REDIS", REDIS);
    MAPPING.put("MEMCACHED", MEMCACHED);
    MAPPING.put("ELASTICSEARCH", ELASTICSEARCH);
    MAPPING.put("OPENSEARCH", OPENSEARCH);
    MAPPING.put("HIBERNATE", HIBERNATE);
    MAPPING.put("AEROSPIKE", AEROSPIKE);
    MAPPING.put("DATANUCLEUS", DATANUCLEUS);
    MAPPING.put("MESSAGE_CLIENT", MESSAGE_CLIENT);
    MAPPING.put("MESSAGE_CONSUMER", MESSAGE_CONSUMER);
    MAPPING.put("MESSAGE_PRODUCER", MESSAGE_PRODUCER);
    MAPPING.put("MESSAGE_BROKER", MESSAGE_BROKER);
    MAPPING.put("GRAPHQL", GRAPHQL);
    MAPPING.put("TEST", TEST);
    MAPPING.put("TEST_SUITE_END", TEST_SUITE_END);
    MAPPING.put("TEST_MODULE_END", TEST_MODULE_END);
    MAPPING.put("TEST_SESSION_END", TEST_SESSION_END);
    MAPPING.put("VULNERABILITY", VULNERABILITY);
    MAPPING.put("PROTOBUF", PROTOBUF);
    MAPPING.put("MULE", MULE);
    MAPPING.put("VALKEY", VALKEY);
    MAPPING.put("WEBSOCKET", WEBSOCKET);
    MAPPING.put("SERVERLESS", SERVERLESS);
    MAPPING.put("LLMOBS", LLMOBS);
  }

  @Override
  protected String className() {
    return "DDSpanTypes";
  }

  @Override
  protected Map<String, String> mapping() {
    return MAPPING;
  }
}
