package datadog.trace.instrumentation.v3_9;

import static datadog.trace.instrumentation.hazelcast.HazelcastConstants.COMPONENT_NAME;
import static datadog.trace.instrumentation.hazelcast.HazelcastConstants.HAZELCAST_INSTANCE;
import static datadog.trace.instrumentation.hazelcast.HazelcastConstants.HAZELCAST_NAME;
import static datadog.trace.instrumentation.hazelcast.HazelcastConstants.HAZELCAST_OPERATION;
import static datadog.trace.instrumentation.hazelcast.HazelcastConstants.HAZELCAST_SERVICE;

import com.hazelcast.core.HazelcastInstance;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import datadog.trace.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Decorate Hazelcast distributed object span's with relevant contextual information. */
public class ClientInvocationDecorator extends ClientDecorator {

  private static final Logger log = LoggerFactory.getLogger(ClientInvocationDecorator.class);

  public static final ClientInvocationDecorator DECORATE = new ClientInvocationDecorator();

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
    return COMPONENT_NAME.toString();
  }

  /** Decorate trace based on service execution metadata. */
  public AgentSpan onServiceExecution(
      final AgentSpan span, final String operationName, final String objectName) {

    if (objectName != null) {
      span.setResourceName(UTF8BytesString.create(Strings.join(" ", operationName, objectName)));
      span.setTag(HAZELCAST_NAME, objectName);
    } else {
      span.setResourceName(UTF8BytesString.create(operationName));
    }

    span.setTag(HAZELCAST_OPERATION, operationName);
    span.setTag(HAZELCAST_SERVICE, operationName.substring(0, operationName.indexOf('.')));

    return span;
  }

  public AgentSpan onHazelcastInstance(final AgentSpan span, HazelcastInstance instance) {

    if (instance != null
        && instance.getLifecycleService() != null
        && instance.getLifecycleService().isRunning()) {
      try {
        span.setTag(HAZELCAST_INSTANCE, instance.getName());
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    return span;
  }

  /** Annotate the span with the results of the operation. */
  public AgentSpan onResult(final AgentSpan span, Object result) {

    // Nothing to do here, so return
    if (result == null) {
      return span;
    }

    return span;
  }
}
