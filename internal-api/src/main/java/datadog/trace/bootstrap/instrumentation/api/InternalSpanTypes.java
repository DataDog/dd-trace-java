package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.DDSpanTypes;

public class InternalSpanTypes {
  public static final CharSequence HTTP_CLIENT =
      UTF8BytesString.createConstant(DDSpanTypes.HTTP_CLIENT);
  public static final CharSequence HTTP_SERVER =
      UTF8BytesString.createConstant(DDSpanTypes.HTTP_SERVER);
  public static final CharSequence RPC = UTF8BytesString.createConstant(DDSpanTypes.RPC);
  public static final CharSequence SOAP = UTF8BytesString.createConstant(DDSpanTypes.SOAP);

  public static final CharSequence SQL = UTF8BytesString.createConstant(DDSpanTypes.SQL);
  public static final CharSequence MONGO = UTF8BytesString.createConstant(DDSpanTypes.MONGO);
  public static final CharSequence CASSANDRA =
      UTF8BytesString.createConstant(DDSpanTypes.CASSANDRA);
  public static final CharSequence COUCHBASE =
      UTF8BytesString.createConstant(DDSpanTypes.COUCHBASE); // Using generic for now.
  public static final CharSequence REDIS = UTF8BytesString.createConstant(DDSpanTypes.REDIS);
  public static final CharSequence MEMCACHED =
      UTF8BytesString.createConstant(DDSpanTypes.MEMCACHED);
  public static final CharSequence ELASTICSEARCH =
      UTF8BytesString.createConstant(DDSpanTypes.ELASTICSEARCH);
  public static final CharSequence HIBERNATE =
      UTF8BytesString.createConstant(DDSpanTypes.HIBERNATE);
  public static final CharSequence AEROSPIKE =
      UTF8BytesString.createConstant(DDSpanTypes.AEROSPIKE);
  public static final CharSequence DATANUCLEUS =
      UTF8BytesString.createConstant(DDSpanTypes.DATANUCLEUS);

  // these are all the same thing but don't want to be taken by surprise by changes in DDSpanTypes
  public static final CharSequence MESSAGE_CONSUMER =
      UTF8BytesString.createConstant(DDSpanTypes.MESSAGE_CONSUMER);
  public static final CharSequence MESSAGE_PRODUCER =
      UTF8BytesString.createConstant(DDSpanTypes.MESSAGE_PRODUCER);
}
