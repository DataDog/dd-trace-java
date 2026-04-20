package datadog.trace.instrumentation.elasticsearch;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

public class ElasticsearchTransportClientDecorator extends DBTypeProcessingDatabaseClientDecorator {

  private static final String DB_TYPE = "elasticsearch";
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service(DB_TYPE);

  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().database().operation(DB_TYPE));
  public static final CharSequence ELASTICSEARCH_JAVA =
      UTF8BytesString.create("elasticsearch-java");

  public static final ElasticsearchTransportClientDecorator DECORATE =
      new ElasticsearchTransportClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"elasticsearch"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return ELASTICSEARCH_JAVA;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.ELASTICSEARCH;
  }

  @Override
  protected String dbType() {
    return DB_TYPE;
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

  public AgentSpan onRequest(final AgentSpan span, final Class action, final Class request) {
    if (action != null) {
      String actionName = action.getSimpleName();
      // ES 7.9 internally changes PutMappingAction to AutoPutMappingAction for
      // documents with unmapped fields; reverse this to get the original action
      if ("AutoPutMappingAction".equals(actionName)) {
        actionName = "PutMappingAction";
      }
      span.setResourceName(actionName);
      span.setTag("elasticsearch.action", actionName);
    }
    if (request != null) {
      span.setTag("elasticsearch.request", request.getSimpleName());
    }
    return span;
  }
}
