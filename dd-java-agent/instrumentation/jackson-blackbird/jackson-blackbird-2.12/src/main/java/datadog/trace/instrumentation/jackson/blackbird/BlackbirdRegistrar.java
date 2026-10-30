package datadog.trace.instrumentation.jackson.blackbird;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlackbirdRegistrar {
  private static final Logger log = LoggerFactory.getLogger(BlackbirdRegistrar.class);

  static final int MAX_REGISTRATIONS = 64;

  private static final AtomicInteger registrations = new AtomicInteger();

  private static volatile boolean loggedFirst;
  private static volatile boolean loggedCap;
  private static volatile boolean failed;

  private BlackbirdRegistrar() {}

  public static void onConstructed(final ObjectMapper mapper) {
    final Class<?> type = mapper.getClass();
    if (failed || type != ObjectMapper.class && type != JsonMapper.class) {
      return;
    }
    if (hasAccessorModule(mapper)) {
      return;
    }
    if (registrations.incrementAndGet() > MAX_REGISTRATIONS) {
      if (!loggedCap) {
        loggedCap = true;
        log.debug(
            "Not registering Jackson Blackbird on more than {} mappers; later mappers keep plain"
                + " reflection",
            MAX_REGISTRATIONS);
      }
      return;
    }
    tryRegister(mapper, new BlackbirdModule());
  }

  static void tryRegister(final ObjectMapper mapper, final Module module) {
    try {
      mapper.registerModule(module);
    } catch (final Throwable t) {
      failed = true;
      log.debug("Jackson Blackbird registration failed; not registering it on later mappers", t);
      return;
    }
    if (!loggedFirst) {
      loggedFirst = true;
      log.debug("Registered Jackson Blackbird module on {}", mapper.getClass().getName());
    }
  }

  static void resetForTests() {
    registrations.set(0);
    failed = false;
  }

  public static boolean hasAccessorModule(final ObjectMapper mapper) {
    for (final Object id : mapper.getRegisteredModuleIds()) {
      final String name = String.valueOf(id);
      if (name.endsWith(".BlackbirdModule") || name.endsWith(".AfterburnerModule")) {
        return true;
      }
    }
    return false;
  }
}
