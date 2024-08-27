package com.datadog.debugger.agent;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThirdPartyLibraries {

  public static final ThirdPartyLibraries INSTANCE = new ThirdPartyLibraries();
  public static final char[] ALPHABET = "abcdefghijklmnopqrstuvwxyz".toCharArray();
  private static final Logger LOGGER = LoggerFactory.getLogger(ThirdPartyLibraries.class);
  private static final JsonAdapter<InternalConfig> ADAPTER =
      new Moshi.Builder().build().adapter(InternalConfig.class);
  private static final String FILE_NAME = "/third_party_libraries.json";

  private ThirdPartyLibraries() {}

  public Set<String> getThirdPartyLibraries(Config config) {
    try (InputStream inputStream = this.getClass().getResourceAsStream(FILE_NAME)) {
      InternalConfig defaults = readConfig(inputStream);
      Set<String> excludes =
          config.getThirdPartyIncludes().stream()
              .filter(s -> !s.isEmpty())
              .collect(Collectors.toSet());
      excludes.addAll(defaults.getPrefixes());
      return excludes;
    } catch (Exception e) {
      LOGGER.error("Failed reading " + FILE_NAME + ". Treating all classes as third party.", e);
      return getExcludeAll();
    }
  }

  public Set<String> getThirdPartyExcludes(Config config) {
    return config.getThirdPartyExcludes().stream()
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toSet());
  }

  // Add a*, b*, c*, ..., z* to the exclude trie in ClassNameFiltering. Simply adding * does not
  // work.
  private static Set<String> getExcludeAll() {
    Set<String> excludeAllPrefixes = new HashSet<>();
    for (char c : ALPHABET) {
      excludeAllPrefixes.add(String.valueOf(c));
    }
    return excludeAllPrefixes;
  }

  private static InternalConfig readConfig(InputStream inputStream) throws IOException {
    try (InputStreamReader isr = new InputStreamReader(inputStream);
        BufferedReader reader = new BufferedReader(isr)) {
      return ADAPTER.fromJson(reader.lines().collect(Collectors.joining(System.lineSeparator())));
    }
  }

  private static class InternalConfig {
    private final String version;
    private final List<String> prefixes;

    public InternalConfig(String version, List<String> prefixes) {
      this.version = version;
      this.prefixes = prefixes;
    }

    public String getVersion() {
      return version;
    }

    public List<String> getPrefixes() {
      return prefixes;
    }
  }
}
