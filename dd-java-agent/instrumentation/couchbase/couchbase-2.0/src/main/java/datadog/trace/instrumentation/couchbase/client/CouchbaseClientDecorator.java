package datadog.trace.instrumentation.couchbase.client;

import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

class CouchbaseClientDecorator extends DBTypeProcessingDatabaseClientDecorator {
  private static final CharSequence COUCHBASE_CLIENT = UTF8BytesString.create("couchbase-client");
  private static final String DB_TYPE = "couchbase";

  private static final String SERVICE_NAME =
      SpanNaming.instance()
          .namingSchema()
          .database()
          .service(Config.get().getServiceName(), DB_TYPE);

  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().database().operation(DB_TYPE));

  public static final CouchbaseClientDecorator DECORATE = new CouchbaseClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"couchbase"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return COUCHBASE_CLIENT;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.COUCHBASE;
  }

  @Override
  protected String dbType() {
    return "couchbase";
  }

  @Override
  protected String dbUser(final Object o) {
    return null;
  }

  @Override
  protected String dbInstance(final Object o) {
    return null;
  }

  @Override
  protected String dbHostname(Object o) {
    return null;
  }
}
