package datadog.trace.core;

import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_INSTANCE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_STATEMENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_USER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_STATUS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.bootstrap.instrumentation.api.Tags.MESSAGE_BUS_DESTINATION;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOSTNAME;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOST_IPV4;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_HOST_IPV6;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_PORT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_SERVICE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SAMPLING_PRIORITY;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

public class StringTables {

  public static final String SERVICE = "service";
  public static final String NAME = "name";
  public static final String RESOURCE = "resource";
  public static final String TRACE_ID = "trace_id";
  public static final String SPAN_ID = "span_id";
  public static final String PARENT_ID = "parent_id";
  public static final String START = "start";
  public static final String DURATION = "duration";
  public static final String TYPE = "type";
  public static final String ERROR = "error";
  public static final String METRICS = "metrics";
  public static final String META = "meta";

  // intentionally not thread safe; must be maintained to be effectively immutable
  // if a constant registration API is added, should be ensured that this is only used during
  // startup
  private static final Map<String, byte[]> UTF8_INTERN_TABLE = new HashMap<>(256);

  static {
    intern(UTF8_INTERN_TABLE, SERVICE, UTF_8);
    intern(UTF8_INTERN_TABLE, NAME, UTF_8);
    intern(UTF8_INTERN_TABLE, RESOURCE, UTF_8);
    intern(UTF8_INTERN_TABLE, TRACE_ID, UTF_8);
    intern(UTF8_INTERN_TABLE, SPAN_ID, UTF_8);
    intern(UTF8_INTERN_TABLE, PARENT_ID, UTF_8);
    intern(UTF8_INTERN_TABLE, START, UTF_8);
    intern(UTF8_INTERN_TABLE, DURATION, UTF_8);
    intern(UTF8_INTERN_TABLE, TYPE, UTF_8);
    intern(UTF8_INTERN_TABLE, ERROR, UTF_8);
    intern(UTF8_INTERN_TABLE, METRICS, UTF_8);
    intern(UTF8_INTERN_TABLE, META, UTF_8);

    // well known tags
    intern(UTF8_INTERN_TABLE, SPAN_KIND_SERVER, UTF_8);
    intern(UTF8_INTERN_TABLE, SPAN_KIND_CLIENT, UTF_8);
    intern(UTF8_INTERN_TABLE, SPAN_KIND_PRODUCER, UTF_8);
    intern(UTF8_INTERN_TABLE, SPAN_KIND_CONSUMER, UTF_8);
    intern(UTF8_INTERN_TABLE, HTTP_URL, UTF_8);
    intern(UTF8_INTERN_TABLE, HTTP_STATUS, UTF_8);
    intern(UTF8_INTERN_TABLE, HTTP_METHOD, UTF_8);
    intern(UTF8_INTERN_TABLE, PEER_HOST_IPV4, UTF_8);
    intern(UTF8_INTERN_TABLE, PEER_HOST_IPV6, UTF_8);
    intern(UTF8_INTERN_TABLE, PEER_SERVICE, UTF_8);
    intern(UTF8_INTERN_TABLE, PEER_HOSTNAME, UTF_8);
    intern(UTF8_INTERN_TABLE, PEER_PORT, UTF_8);
    intern(UTF8_INTERN_TABLE, SAMPLING_PRIORITY, UTF_8);
    intern(UTF8_INTERN_TABLE, SPAN_KIND, UTF_8);
    intern(UTF8_INTERN_TABLE, COMPONENT, UTF_8);
    intern(UTF8_INTERN_TABLE, DB_TYPE, UTF_8);
    intern(UTF8_INTERN_TABLE, DB_INSTANCE, UTF_8);
    intern(UTF8_INTERN_TABLE, DB_USER, UTF_8);
    intern(UTF8_INTERN_TABLE, DB_STATEMENT, UTF_8);
    intern(UTF8_INTERN_TABLE, MESSAGE_BUS_DESTINATION, UTF_8);
  }

  public static byte[] getBytesUTF8(String value) {
    byte[] bytes = UTF8_INTERN_TABLE.get(value);
    return null == bytes ? value.getBytes(UTF_8) : bytes;
  }

  private static void intern(Map<String, byte[]> table, String value, Charset encoding) {
    table.put(value, value.getBytes(encoding));
  }
}
