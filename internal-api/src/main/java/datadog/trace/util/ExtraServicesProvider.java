package datadog.trace.util;

import datadog.trace.api.Config;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExtraServicesProvider {

  private static final Logger log = LoggerFactory.getLogger(ExtraServicesProvider.class);

  private static final int MAX_EXTRA_SERVICE = Config.get().getRemoteConfigMaxExtraServices();

  // This is not final to allow mocking it on tests
  private static ExtraServicesProvider INSTANCE = new ExtraServicesProvider();

  public static ExtraServicesProvider get() {
    return INSTANCE;
  }

  private final ConcurrentHashMap<String, String> extraServices = new ConcurrentHashMap<>();;

  volatile boolean limitReachedLogged = false;

  ExtraServicesProvider() {
    // singleton
  }

  public void maybeAddExtraService(final String serviceName) {
    if (serviceName == null || serviceName.isEmpty()) {
      return;
    }
    if (extraServices.size() >= MAX_EXTRA_SERVICE) {
      if (!limitReachedLogged) {
        log.debug(
            "extra service limit({}) reached: service {} can't be added",
            MAX_EXTRA_SERVICE,
            serviceName);
        limitReachedLogged = true;
      }
      return;
    }
    if (!Config.get().getServiceName().equalsIgnoreCase(serviceName)) {
      extraServices.put(serviceName.toLowerCase(Locale.ROOT), serviceName);
    }
  }

  @Nullable
  public List<String> getExtraServices() {
    return extraServices.isEmpty() ? null : new ArrayList<>(extraServices.values());
  }

  public void clear() {
    extraServices.clear();
  }
}
