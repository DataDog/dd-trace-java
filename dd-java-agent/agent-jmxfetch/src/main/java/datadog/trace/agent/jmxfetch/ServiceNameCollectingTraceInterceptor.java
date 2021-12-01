package datadog.trace.agent.jmxfetch;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import org.datadog.jmxfetch.service.ServiceNameProvider;

public class ServiceNameCollectingTraceInterceptor
    implements TraceInterceptor, ServiceNameProvider {
  /*
   * These span types all set their own service names, so we ignore them. These should not have JVM
   * runtime metrics applied to their service names.
   */
  private static final Set<String> IGNORED_ENTRY_SPAN_TYPES =
      new HashSet<>(
          Arrays.asList(
              DDSpanTypes.SQL,
              DDSpanTypes.MONGO,
              DDSpanTypes.CASSANDRA,
              DDSpanTypes.COUCHBASE,
              DDSpanTypes.REDIS,
              DDSpanTypes.MEMCACHED,
              DDSpanTypes.ELASTICSEARCH,
              DDSpanTypes.HIBERNATE,
              DDSpanTypes.AEROSPIKE,
              DDSpanTypes.DATANUCLEUS,
              DDSpanTypes.MESSAGE_CLIENT,
              DDSpanTypes.MESSAGE_CONSUMER,
              DDSpanTypes.MESSAGE_PRODUCER,
              DDSpanTypes.MESSAGE_BROKER));

  private final Set<String> serviceNames = new ConcurrentSkipListSet<>();

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {
    if (!trace.isEmpty()) {
      MutableSpan rootSpan = trace.iterator().next().getLocalRootSpan();
      if (!IGNORED_ENTRY_SPAN_TYPES.contains(rootSpan.getSpanType())) {
        serviceNames.add(rootSpan.getServiceName());
      }
    }
    return trace;
  }

  @Override
  public int priority() {
    return Integer.MAX_VALUE;
  }

  @Override
  public Iterable<String> getServiceNames() {
    return new ArrayList<>(serviceNames);
  }
}
