package datadog.trace.bootstrap.otel.instrumentation.internal;

import datadog.config.ConfigProvider;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redirects requests to our own {@link ConfigProvider}. */
public final class InstrumentationConfig {
  private static final InstrumentationConfig INSTANCE = new InstrumentationConfig();

  private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)(ms|[DdHhMmSs]?)");

  private static final ConfigProvider delegate = ConfigProvider.getInstance();

  public static InstrumentationConfig get() {
    return INSTANCE;
  }

  public String getString(String name) {
    return delegate.getString(name);
  }

  public String getString(String name, String defaultValue) {
    return delegate.getString(name, defaultValue);
  }

  public boolean getBoolean(String name, boolean defaultValue) {
    return delegate.getBoolean(name, defaultValue);
  }

  public int getInt(String name, int defaultValue) {
    return delegate.getInteger(name, defaultValue);
  }

  public long getLong(String name, long defaultValue) {
    return delegate.getLong(name, defaultValue);
  }

  public double getDouble(String name, double defaultValue) {
    return delegate.getDouble(name, defaultValue);
  }

  public Duration getDuration(String name, Duration defaultValue) {
    String durationString = delegate.getString(name);
    if (null == durationString) {
      return defaultValue;
    }
    Matcher matcher = DURATION_PATTERN.matcher(durationString);
    if (matcher.matches()) {
      long value = Integer.parseInt(matcher.group(1));
      String unit = matcher.group(2);
      if ("D".equalsIgnoreCase(unit)) {
        return Duration.ofDays(value);
      } else if ("H".equalsIgnoreCase(unit)) {
        return Duration.ofHours(value);
      } else if ("M".equalsIgnoreCase(unit)) {
        return Duration.ofMinutes(value);
      } else if ("S".equalsIgnoreCase(unit)) {
        return Duration.ofSeconds(value);
      } else {
        return Duration.ofMillis(value); // already in ms
      }
    } else {
      throw new IllegalArgumentException(
          "Invalid duration property " + name + "=" + durationString);
    }
  }

  public List<String> getList(String name) {
    return getList(name, Collections.emptyList());
  }

  public List<String> getList(String name, List<String> defaultValue) {
    return delegate.getList(name, defaultValue);
  }

  public Set<String> getSet(String name, Set<String> defaultValue) {
    return delegate.getSet(name, defaultValue);
  }

  public Map<String, String> getMap(String name, Map<String, String> defaultValue) {
    Map<String, String> map = delegate.getMergedMap(name);
    if (map.isEmpty()) {
      map = defaultValue;
    }
    return map;
  }
}
