package datadog.trace.junit.utils.tabletest;

import datadog.trace.api.DDSpanTypes;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.params.converter.ArgumentConversionException;
import org.junit.jupiter.params.converter.ArgumentConverter;

public class DDSpanTypesConverter implements ArgumentConverter {

  @Override
  public Object convert(Object source, ParameterContext context)
      throws ArgumentConversionException {
    if (source == null) {
      return null;
    }
    if (source.toString().startsWith("DDSpanTypes.")) {
      switch (source.toString()) {
        case "DDSpanTypes.HTTP_CLIENT":
          return DDSpanTypes.HTTP_CLIENT;
        case "DDSpanTypes.HTTP_SERVER":
          return DDSpanTypes.HTTP_SERVER;
        case "DDSpanTypes.RPC":
          return DDSpanTypes.RPC;
        case "DDSpanTypes.CACHE":
          return DDSpanTypes.CACHE;
        case "DDSpanTypes.SOAP":
          return DDSpanTypes.SOAP;
        case "DDSpanTypes.SQL":
          return DDSpanTypes.SQL;
        case "DDSpanTypes.MONGO":
          return DDSpanTypes.MONGO;
        case "DDSpanTypes.CASSANDRA":
          return DDSpanTypes.CASSANDRA;
        case "DDSpanTypes.COUCHBASE":
          return DDSpanTypes.COUCHBASE;
        case "DDSpanTypes.REDIS":
          return DDSpanTypes.REDIS;
        case "DDSpanTypes.MEMCACHED":
          return DDSpanTypes.MEMCACHED;
        case "DDSpanTypes.ELASTICSEARCH":
          return DDSpanTypes.ELASTICSEARCH;
        case "DDSpanTypes.OPENSEARCH":
          return DDSpanTypes.OPENSEARCH;
        case "DDSpanTypes.HIBERNATE":
          return DDSpanTypes.HIBERNATE;
        case "DDSpanTypes.AEROSPIKE":
          return DDSpanTypes.AEROSPIKE;
        case "DDSpanTypes.DATANUCLEUS":
          return DDSpanTypes.DATANUCLEUS;
        case "DDSpanTypes.MESSAGE_CLIENT":
          return DDSpanTypes.MESSAGE_CLIENT;
        case "DDSpanTypes.MESSAGE_CONSUMER":
          return DDSpanTypes.MESSAGE_CONSUMER;
        case "DDSpanTypes.MESSAGE_PRODUCER":
          return DDSpanTypes.MESSAGE_PRODUCER;
        case "DDSpanTypes.MESSAGE_BROKER":
          return DDSpanTypes.MESSAGE_BROKER;
        case "DDSpanTypes.GRAPHQL":
          return DDSpanTypes.GRAPHQL;
        case "DDSpanTypes.TEST":
          return DDSpanTypes.TEST;
        case "DDSpanTypes.TEST_SUITE_END":
          return DDSpanTypes.TEST_SUITE_END;
        case "DDSpanTypes.TEST_MODULE_END":
          return DDSpanTypes.TEST_MODULE_END;
        case "DDSpanTypes.TEST_SESSION_END":
          return DDSpanTypes.TEST_SESSION_END;
        case "DDSpanTypes.VULNERABILITY":
          return DDSpanTypes.VULNERABILITY;
        case "DDSpanTypes.PROTOBUF":
          return DDSpanTypes.PROTOBUF;
        case "DDSpanTypes.MULE":
          return DDSpanTypes.MULE;
        case "DDSpanTypes.VALKEY":
          return DDSpanTypes.VALKEY;
        case "DDSpanTypes.WEBSOCKET":
          return DDSpanTypes.WEBSOCKET;
        case "DDSpanTypes.SERVERLESS":
          return DDSpanTypes.SERVERLESS;
        case "DDSpanTypes.LLMOBS":
          return DDSpanTypes.LLMOBS;
        default:
          throw new ArgumentConversionException("Cannot convert " + source);
      }
    }
    return source;
  }
}
