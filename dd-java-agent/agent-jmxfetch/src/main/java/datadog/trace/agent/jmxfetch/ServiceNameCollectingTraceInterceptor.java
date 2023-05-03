package datadog.trace.agent.jmxfetch;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.interceptor.AbstractTraceInterceptor;
import datadog.trace.api.interceptor.MutableSpan;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.datadog.jmxfetch.service.ServiceNameProvider;

public class ServiceNameCollectingTraceInterceptor extends AbstractTraceInterceptor
    implements ServiceNameProvider {

  public static final ServiceNameCollectingTraceInterceptor INSTANCE =
      new ServiceNameCollectingTraceInterceptor(Priority.SERVICE_NAME_COLLECTING);

  /*
   * The other span types all set their own service names, so we ignore them. They should not have JVM
   * runtime metrics applied to their service names.
   */
  private static final Set<String> VALID_ENTRY_SPAN_TYPES =
      new HashSet<>(Arrays.asList(DDSpanTypes.HTTP_SERVER, DDSpanTypes.RPC, DDSpanTypes.SOAP));
  private static final int SERVICE_NAME_LIMIT =
      Config.get().getJmxFetchMultipleRuntimeServicesLimit();
  private static final AtomicIntegerFieldUpdater<ServiceNameCollectingTraceInterceptor>
      SERVICE_NAMES_SIZE_UPDATER =
          AtomicIntegerFieldUpdater.newUpdater(
              ServiceNameCollectingTraceInterceptor.class, "serviceNamesSize");

  private volatile int serviceNamesSize = 0;
  private final ConcurrentHashMap<String, Boolean> serviceNames = new ConcurrentHashMap<>();

  protected ServiceNameCollectingTraceInterceptor(Priority priority) {
    super(priority);
  }

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {
    if (!trace.isEmpty()) {
      MutableSpan rootSpan = trace.iterator().next().getLocalRootSpan();
      if (VALID_ENTRY_SPAN_TYPES.contains(rootSpan.getSpanType())) {
        // Not a hard limit, the race here is acceptable to not add locking on every trace report
        if (serviceNamesSize < SERVICE_NAME_LIMIT) {
          if (serviceNames.putIfAbsent(rootSpan.getServiceName(), Boolean.TRUE) == null) {
            SERVICE_NAMES_SIZE_UPDATER.incrementAndGet(this);
          }
        }
      }
    }
    return trace;
  }

  @Override
  public Iterable<String> getServiceNames() {
    return serviceNames.keySet();
  }
}
