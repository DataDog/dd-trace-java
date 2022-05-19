package datadog.trace.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.TreeSet;

public class ConfigCollector extends LinkedHashMap<String, Object> {

  public static final Set<String> CONFIG_FILTER_LIST =
      new TreeSet<>(
          Arrays.asList(
              "dd_api_key",
              "dd.api-key",
              "dd.profiling.api-key",
              "dd.profiling.apikey"
              ));

  public static class Holder {
    public static final ConfigCollector INSTANCE = new ConfigCollector();
  }

  public static ConfigCollector get() {
    return Holder.INSTANCE;
  }

  @Override
  public Object put(String key, Object value) {
    if (CONFIG_FILTER_LIST.contains(key)
    ) {
      value = "<hidden>";
    }
    return super.put(key, value);
  }
}
