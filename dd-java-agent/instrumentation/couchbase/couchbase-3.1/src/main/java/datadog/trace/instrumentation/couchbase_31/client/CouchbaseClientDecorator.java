package datadog.trace.instrumentation.couchbase_31.client;

import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_TYPE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DatabaseClientDecorator;

class CouchbaseClientDecorator extends DatabaseClientDecorator {
  public static final CouchbaseClientDecorator DECORATE = new CouchbaseClientDecorator();

  public static final CharSequence COUCHBASE_CLIENT = UTF8BytesString.create("couchbase-client");
  public static final String COUCHBASE = "couchbase";

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    span.setServiceName(dbType());
    span.setTag(DB_TYPE, dbType());
    return super.afterStart(span);
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {COUCHBASE};
  }

  @Override
  protected String service() {
    return COUCHBASE;
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
    return COUCHBASE;
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
