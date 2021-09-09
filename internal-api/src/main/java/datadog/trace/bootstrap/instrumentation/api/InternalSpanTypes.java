package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDSpanTypes;

public class InternalSpanTypes {
  public static final CharSequence HTTP_CLIENT = UTF8BytesString.create(DDSpanTypes.HTTP_CLIENT);
  public static final CharSequence HTTP_SERVER = UTF8BytesString.create(DDSpanTypes.HTTP_SERVER);
  public static final CharSequence RPC = UTF8BytesString.create(DDSpanTypes.RPC);
  public static final CharSequence SOAP = UTF8BytesString.create(DDSpanTypes.SOAP);

  public static final CharSequence SQL = UTF8BytesString.create(DDSpanTypes.SQL);
  public static final CharSequence MONGO = UTF8BytesString.create(DDSpanTypes.MONGO);
  public static final CharSequence CASSANDRA = UTF8BytesString.create(DDSpanTypes.CASSANDRA);
  public static final CharSequence COUCHBASE =
      UTF8BytesString.create(DDSpanTypes.COUCHBASE); // Using generic for now.
  public static final CharSequence REDIS = UTF8BytesString.create(DDSpanTypes.REDIS);
  public static final CharSequence MEMCACHED = UTF8BytesString.create(DDSpanTypes.MEMCACHED);
  public static final CharSequence ELASTICSEARCH =
      UTF8BytesString.create(DDSpanTypes.ELASTICSEARCH);
  public static final CharSequence HIBERNATE = UTF8BytesString.create(DDSpanTypes.HIBERNATE);
  public static final CharSequence AEROSPIKE = UTF8BytesString.create(DDSpanTypes.AEROSPIKE);
  public static final CharSequence DATANUCLEUS = UTF8BytesString.create(DDSpanTypes.DATANUCLEUS);

  // these are all the same thing but don't want to be taken by surprise by changes in DDSpanTypes
  public static final CharSequence MESSAGE_CLIENT =
      UTF8BytesString.create(DDSpanTypes.MESSAGE_CLIENT);
  public static final CharSequence MESSAGE_CONSUMER =
      UTF8BytesString.create(DDSpanTypes.MESSAGE_CONSUMER);
  public static final CharSequence MESSAGE_PRODUCER =
      UTF8BytesString.create(DDSpanTypes.MESSAGE_PRODUCER);
}
