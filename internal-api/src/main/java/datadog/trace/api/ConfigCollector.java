package datadog.trace.api;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects system properties and environment variables set by the user and used by the tracer. Puts
 * to this map will happen in Config and ConfigProvider classes, which can run concurrently with
 * consumers. So this is based on a ConcurrentHashMap to deal with it.
 */
public class ConfigCollector extends ConcurrentHashMap<String, Object> {

  private static final Set<String> CONFIG_FILTER_LIST =
      new HashSet<>(
          Arrays.asList("DD_API_KEY", "dd.api-key", "dd.profiling.api-key", "dd.profiling.apikey"));

  private static class Holder {
    public static final ConfigCollector INSTANCE = new ConfigCollector();
  }

  public static ConfigCollector get() {
    return Holder.INSTANCE;
  }

  @Override
  public Object put(String key, Object value) {
    if (CONFIG_FILTER_LIST.contains(key)) {
      value = "<hidden>";
    }
    return super.put(key, value);
  }
}
