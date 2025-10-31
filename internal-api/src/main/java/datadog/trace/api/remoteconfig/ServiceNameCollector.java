package datadog.trace.api.remoteconfig;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServiceNameCollector {

  private static final Logger log = LoggerFactory.getLogger(ServiceNameCollector.class);

  private static final int MAX_EXTRA_SERVICE = Config.get().getRemoteConfigMaxExtraServices();

  // This is not final to allow mocking it on tests
  private static ServiceNameCollector INSTANCE = new ServiceNameCollector();

  public static ServiceNameCollector get() {
    return INSTANCE;
  }

  private final ConcurrentHashMap<String, String> services =
      new ConcurrentHashMap<>(MAX_EXTRA_SERVICE);

  volatile boolean limitReachedLogged = false;

  private ServiceNameCollector() {
    // singleton
  }

  public void addService(final String serviceName) {
    if (serviceName == null || serviceName.isEmpty()) {
      return;
    }
    if (services.size() >= MAX_EXTRA_SERVICE) {
      if (!limitReachedLogged) {
        log.debug(
            SEND_TELEMETRY,
            "extra service limit({}) reached: service {} can't be added",
            MAX_EXTRA_SERVICE,
            serviceName);
        limitReachedLogged = true;
      }
      return;
    }
    services.putIfAbsent(serviceName, serviceName);
  }

  /**
   * Get the list of unique services deduplicated by case. There is no locking on the addService map
   * so, the method is not thread safe.
   *
   * @return
   */
  @Nullable
  public List<String> getServices() {
    if (services.isEmpty()) {
      return null;
    }
    final Set<String> uniqueNames = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    uniqueNames.addAll(services.keySet());
    uniqueNames.remove(Config.get().getServiceName());
    return uniqueNames.isEmpty() ? null : new ArrayList<>(uniqueNames);
  }

  public void clear() {
    services.clear();
  }
}
