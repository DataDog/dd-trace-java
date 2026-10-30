package datadog.trace.instrumentation.jackson.blackbird;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Decides whether a freshly constructed mapper gets Blackbird, and registers it. */
public final class BlackbirdRegistrar {
  private static final Logger log = LoggerFactory.getLogger(BlackbirdRegistrar.class);

  private static volatile boolean loggedFirst;

  private BlackbirdRegistrar() {}

  /**
   * Called when the base {@code ObjectMapper} constructor returns. Only plain {@code ObjectMapper}
   * and {@code JsonMapper} instances are touched: subclasses (XmlMapper, YAMLMapper, application
   * subclasses) still have constructor code to run and may rely on their own serializer factories.
   */
  public static void onConstructed(final ObjectMapper mapper) {
    final Class<?> type = mapper.getClass();
    if (type != ObjectMapper.class && type != JsonMapper.class) {
      return;
    }
    if (hasAccessorModule(mapper)) {
      return;
    }
    mapper.registerModule(new BlackbirdModule());
    if (!loggedFirst) {
      loggedFirst = true;
      log.debug("Registered Jackson Blackbird module on {}", type.getName());
    }
  }

  /** True when Blackbird or Afterburner is already registered on the mapper. */
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
