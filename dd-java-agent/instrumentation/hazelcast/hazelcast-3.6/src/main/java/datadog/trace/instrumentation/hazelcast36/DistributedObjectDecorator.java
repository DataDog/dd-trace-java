package datadog.trace.instrumentation.hazelcast36;

import static datadog.trace.instrumentation.hazelcast36.HazelcastConstants.COMPONENT_NAME;
import static datadog.trace.instrumentation.hazelcast36.HazelcastConstants.HAZELCAST_NAME;
import static datadog.trace.instrumentation.hazelcast36.HazelcastConstants.HAZELCAST_OPERATION;
import static datadog.trace.instrumentation.hazelcast36.HazelcastConstants.HAZELCAST_SERVICE;
import static datadog.trace.instrumentation.hazelcast36.HazelcastConstants.INSTRUMENTATION_NAME;

import com.hazelcast.core.DistributedObject;
import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.util.function.Function;

/** Decorate Hazelcast distributed object span's with relevant contextual information. */
public class DistributedObjectDecorator extends ClientDecorator {

  public static final DistributedObjectDecorator DECORATE = new DistributedObjectDecorator();

  private static final DDCache<Pair<String, String>, String> QUALIFIED_NAME_CACHE =
      DDCaches.newFixedSizeCache(64);

  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().cache().service(INSTRUMENTATION_NAME);

  private static final Function<Pair<String, String>, String> COMPUTE_QUALIFIED_NAME =
      // Uses inner class for predictable name for Instrumenter.Default.helperClassNames()
      new Function<Pair<String, String>, String>() {
        @Override
        public String apply(Pair<String, String> input) {
          final String service = input.getLeft();
          final String objectName = input.getRight();

          final StringBuilder qualifiedName = new StringBuilder();
          boolean terminateBracket = false;

          if (service != null && service.length() > 15 && service.startsWith("hz:impl:")) {
            // Transform into just the service qualifiedName
            qualifiedName.append(
                service, "hz:impl:".length(), service.length() - "Service".length());
            qualifiedName.append('[');
            terminateBracket = true;
          }

          qualifiedName.append(objectName);

          if (terminateBracket) qualifiedName.append(']');

          return qualifiedName.toString();
        }
      };

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
      final AgentSpan span, final DistributedObject object, final String methodName) {

    final String objectName =
        QUALIFIED_NAME_CACHE.computeIfAbsent(
            Pair.of(object.getServiceName(), object.getName()), COMPUTE_QUALIFIED_NAME);

    span.setResourceName(UTF8BytesString.create(String.join(".", objectName, methodName)));

    span.setTag(HAZELCAST_SERVICE, object.getServiceName());
    span.setTag(HAZELCAST_OPERATION, methodName);
    span.setTag(HAZELCAST_NAME, objectName);

    return span;
  }

  /** Annotate the span with the results of the operation. */
  public AgentSpan onResult(final AgentSpan span, Object result) {
    return span;
  }
}
