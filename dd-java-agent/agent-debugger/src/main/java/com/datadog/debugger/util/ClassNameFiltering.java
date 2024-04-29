package com.datadog.debugger.util;

import com.datadog.debugger.agent.ThirdPartyLibraries;
import datadog.trace.api.Config;
import datadog.trace.util.ClassNameTrie;
import java.util.Collections;
import java.util.Set;

/** A class to filter out classes based on their package name. */
public class ClassNameFiltering {

  private final ClassNameTrie includeTrie;
  private final ClassNameTrie excludeTrie;

  public ClassNameFiltering(Config config) {
    this(
        ThirdPartyLibraries.INSTANCE.getThirdPartyLibraries(config),
        ThirdPartyLibraries.INSTANCE.getThirdPartyExcludes(config));
  }

  public ClassNameFiltering(Set<String> excludes) {
    this(excludes, Collections.emptySet());
  }

  public ClassNameFiltering(Set<String> excludes, Set<String> includes) {
    ClassNameTrie.Builder excludeBuilder = new ClassNameTrie.Builder();
    excludes.forEach(s -> excludeBuilder.put(s + "*", 1));
    this.excludeTrie = excludeBuilder.buildTrie();
    ClassNameTrie.Builder includeBuilder = new ClassNameTrie.Builder();
    includes.forEach(s -> includeBuilder.put(s + "*", 1));
    this.includeTrie = includeBuilder.buildTrie();
  }

  public boolean isExcluded(String className) {
    return includeTrie.apply(className) < 0 && excludeTrie.apply(className) > 0;
  }

  public static ClassNameFiltering allowAll() {
    return new ClassNameFiltering(Collections.emptySet(), Collections.emptySet());
  }
}
