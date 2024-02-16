package datadog.trace.api.remoteconfig;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;

import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

  ServiceNameCollector() {
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
    if (!Config.get().getServiceName().equalsIgnoreCase(serviceName)) {
      services.put(serviceName.toLowerCase(Locale.ROOT), serviceName);
    }
  }

  @Nullable
  public List<String> getServices() {
    return services.isEmpty() ? null : new ArrayList<>(services.values());
  }

  public void clear() {
    services.clear();
  }
}
