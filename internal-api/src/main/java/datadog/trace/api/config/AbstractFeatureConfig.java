package datadog.trace.api.config;

import datadog.trace.bootstrap.config.provider.ConfigProvider;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.Nonnull;

public class AbstractFeatureConfig {
  protected final ConfigProvider configProvider;

  public AbstractFeatureConfig(ConfigProvider configProvider) {
    this.configProvider = configProvider;
  }

  protected boolean isEnabled(
      final boolean defaultEnabled, final String settingName, String settingSuffix) {
    return isEnabled(Collections.singletonList(settingName), "", settingSuffix, defaultEnabled);
  }

  protected boolean isEnabled(
      final Iterable<String> integrationNames,
      final String settingPrefix,
      final String settingSuffix,
      final boolean defaultEnabled) {
    // If default is enabled, we want to disable individually.
    // If default is disabled, we want to enable individually.
    boolean anyEnabled = defaultEnabled;
    for (final String name : integrationNames) {
      final String configKey = settingPrefix + name + settingSuffix;
      final String fullKey = configKey.startsWith("trace.") ? configKey : "trace." + configKey;
      final boolean configEnabled =
          this.configProvider.getBoolean(fullKey, defaultEnabled, configKey);
      if (defaultEnabled) {
        anyEnabled &= configEnabled;
      } else {
        anyEnabled |= configEnabled;
      }
    }
    return anyEnabled;
  }

  @Nonnull
  public static Set<String> parseStringIntoSetOfNonEmptyStrings(final String str) {
    // Using LinkedHashSet to preserve original string order
    final Set<String> result = new LinkedHashSet<>();
    // Java returns single value when splitting an empty string. We do not need that value, so
    // we need to throw it out.
    int start = 0;
    int i = 0;
    for (; i < str.length(); ++i) {
      char c = str.charAt(i);
      if (Character.isWhitespace(c) || c == ',') {
        if (i - start - 1 > 0) {
          result.add(str.substring(start, i));
        }
        start = i + 1;
      }
    }
    if (i - start - 1 > 0) {
      result.add(str.substring(start));
    }
    return Collections.unmodifiableSet(result);
  }
}
