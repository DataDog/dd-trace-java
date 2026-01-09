package datadog.trace.instrumentation.opensearch;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

public class OpensearchTransportClientDecorator extends DBTypeProcessingDatabaseClientDecorator {

  private static final String DB_TYPE = "opensearch";
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service(DB_TYPE);

  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().database().operation(DB_TYPE));
  public static final CharSequence OPENSEARCH_JAVA = UTF8BytesString.create("opensearch-java");

  public static final OpensearchTransportClientDecorator DECORATE =
      new OpensearchTransportClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"opensearch"};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return OPENSEARCH_JAVA;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.OPENSEARCH;
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
      if ("AutoPutMappingAction".equals(actionName)) {
        actionName = "PutMappingAction";
      }
      span.setResourceName(actionName);
      span.setTag("opensearch.action", actionName);
    }
    if (request != null) {
      span.setTag("opensearch.request", request.getSimpleName());
    }
    return span;
  }
}
