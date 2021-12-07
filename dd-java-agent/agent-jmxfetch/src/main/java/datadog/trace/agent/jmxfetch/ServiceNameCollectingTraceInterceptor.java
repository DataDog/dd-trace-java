package datadog.trace.agent.jmxfetch;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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

  private final Map<String, Boolean> serviceNames =
      new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        private static final int MAX_ENTRIES = 128;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
          return size() > MAX_ENTRIES;
        }
      };
  private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
  private final ReentrantReadWriteLock.ReadLock readLock = readWriteLock.readLock();
  private final ReentrantReadWriteLock.WriteLock writeLock = readWriteLock.writeLock();

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {
    if (!trace.isEmpty()) {
      MutableSpan rootSpan = trace.iterator().next().getLocalRootSpan();
      if (!IGNORED_ENTRY_SPAN_TYPES.contains(rootSpan.getSpanType())) {
        try {
          writeLock.lock();
          serviceNames.put(rootSpan.getServiceName(), Boolean.TRUE);
        } finally {
          writeLock.unlock();
        }
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
    try {
      readLock.lock();
      return Arrays.asList(serviceNames.keySet().toArray(new String[0]));
    } finally {
      readLock.unlock();
    }
  }
}
