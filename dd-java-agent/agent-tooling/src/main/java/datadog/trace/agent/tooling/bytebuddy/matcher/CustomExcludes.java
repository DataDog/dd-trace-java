package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.api.Config;
import datadog.trace.util.ClassNameTrie;

public class CustomExcludes {
  private CustomExcludes() {}

  private static final ClassNameTrie excludes;

  static {
    ClassNameTrie.Builder builder = new ClassNameTrie.Builder();
    for (String name : Config.get().getExcludedClasses()) {
      builder.put(name, 1);
    }
    excludes = !builder.isEmpty() ? builder.buildTrie() : null;
  }

  public static boolean isExcluded(String name) {
    return excludes != null && excludes.apply(name) > 0;
  }
}
