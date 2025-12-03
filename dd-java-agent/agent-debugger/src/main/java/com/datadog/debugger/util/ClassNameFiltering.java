package com.datadog.debugger.util;

import com.datadog.debugger.agent.ThirdPartyLibraries;
import datadog.instrument.utils.ClassNameTrie;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

/** A class to filter out classes based on their package name. */
public class ClassNameFiltering implements ClassNameFilter {
  private static final Pattern LAMBDA_PROXY_CLASS_PATTERN = Pattern.compile(".*\\$\\$Lambda.*/.*");

  private final ClassNameTrie includeTrie;
  private final ClassNameTrie excludeTrie;
  private final ClassNameTrie shadingTrie;

  public ClassNameFiltering(Config config) {
    this(
        ThirdPartyLibraries.INSTANCE.getThirdPartyLibraries(config),
        ThirdPartyLibraries.INSTANCE.getThirdPartyExcludes(config),
        ThirdPartyLibraries.INSTANCE.getShadingIdentifiers(config));
  }

  public ClassNameFiltering(Set<String> excludes) {
    this(excludes, Collections.emptySet(), Collections.emptySet());
  }

  public ClassNameFiltering(
      Set<String> excludes, Set<String> includes, Set<String> shadingIdentifiers) {
    ClassNameTrie.Builder excludeBuilder = new ClassNameTrie.Builder();
    excludes.forEach(s -> excludeBuilder.put(s + "*", 1));
    this.excludeTrie = excludeBuilder.buildTrie();
    ClassNameTrie.Builder includeBuilder = new ClassNameTrie.Builder();
    includes.forEach(s -> includeBuilder.put(s + "*", 1));
    this.includeTrie = includeBuilder.buildTrie();
    ClassNameTrie.Builder shadingBuilder = new ClassNameTrie.Builder();
    shadingIdentifiers.forEach(s -> shadingBuilder.put(s + "*", 1));
    this.shadingTrie = shadingBuilder.buildTrie();
  }

  // className is the fully qualified class name with '.' (Java type) notation
  public boolean isExcluded(String className) {
    int shadedIdx = shadedIndexOf(className);
    shadedIdx = Math.max(shadedIdx, 0);
    return (includeTrie.apply(className, shadedIdx) < 0
            && excludeTrie.apply(className, shadedIdx) > 0)
        || isLambdaProxyClass(className);
  }

  static boolean isLambdaProxyClass(String className) {
    return LAMBDA_PROXY_CLASS_PATTERN.matcher(className).matches();
  }

  int shadedIndexOf(String className) {
    int idx = 0;
    int previousIdx = 0;
    while ((idx = className.indexOf('.', previousIdx)) > 0) {
      if (shadingTrie.apply(className, previousIdx) > 0) {
        return idx + 1;
      }
      idx++;
      previousIdx = idx;
    }
    return -1;
  }

  public static ClassNameFiltering allowAll() {
    return new ClassNameFiltering(
        Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
  }
}
