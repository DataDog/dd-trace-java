package datadog.trace.instrumentation.hazelcast4;

import static datadog.trace.instrumentation.hazelcast4.HazelcastConstants.COMPONENT_NAME;
import static datadog.trace.instrumentation.hazelcast4.HazelcastConstants.HAZELCAST_CORRELATION_ID;
import static datadog.trace.instrumentation.hazelcast4.HazelcastConstants.HAZELCAST_NAME;
import static datadog.trace.instrumentation.hazelcast4.HazelcastConstants.HAZELCAST_OPERATION;
import static datadog.trace.instrumentation.hazelcast4.HazelcastConstants.HAZELCAST_SERVICE;
import static datadog.trace.instrumentation.hazelcast4.HazelcastConstants.INSTRUMENTATION_NAME;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;

/** Decorate Hazelcast distributed object span's with relevant contextual information. */
public class HazelcastDecorator extends ClientDecorator {

  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service(INSTRUMENTATION_NAME);

  public static final HazelcastDecorator DECORATE = new HazelcastDecorator();

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_CLIENT;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {COMPONENT_NAME.toString()};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  /** Decorate trace based on service execution metadata. */
  public AgentSpan onServiceExecution(
      final AgentSpan span,
      final String operationName,
      final Object objectName,
      long correlationId) {

    if (objectName != null) {
      span.setResourceName(
          UTF8BytesString.create(String.join(" ", operationName, objectName.toString())));
      span.setTag(HAZELCAST_NAME, objectName.toString());
    } else {
      span.setResourceName(UTF8BytesString.create(operationName));
    }

    span.setTag(HAZELCAST_OPERATION, operationName);
    span.setTag(HAZELCAST_SERVICE, operationName.substring(0, operationName.indexOf('.')));

    if (correlationId > 0) {
      span.setTag(HAZELCAST_CORRELATION_ID, correlationId);
    }

    return span;
  }
}
