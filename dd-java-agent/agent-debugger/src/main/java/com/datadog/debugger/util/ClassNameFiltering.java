package com.datadog.debugger.util;

import com.datadog.debugger.agent.ThirdPartyLibraries;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.DebuggerContext.ClassNameFilter;
import datadog.trace.util.ClassNameTrie;
import datadog.trace.util.Strings;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

/** A class to filter out classes based on their package name. */
public class ClassNameFiltering implements ClassNameFilter {
  private static final Pattern LAMBDA_PROXY_CLASS_PATTERN = Pattern.compile(".*\\$\\$Lambda.*/.*");

  private final ClassNameTrie includeTrie;
  private final ClassNameTrie excludeTrie;
  private final Set<String> shadingIdentifiers;

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
    this.shadingIdentifiers = shadingIdentifiers;
  }

  // className is the fully qualified class name with '.' (Java type) notation
  public boolean isExcluded(String className) {
    return (includeTrie.apply(className) < 0 && excludeTrie.apply(className) > 0)
        || isLambdaProxyClass(className)
        || isShaded(className);
  }

  static boolean isLambdaProxyClass(String className) {
    return LAMBDA_PROXY_CLASS_PATTERN.matcher(className).matches();
  }

  boolean isShaded(String className) {
    String packageName = Strings.getPackageName(className);
    for (String shadingIdentifier : shadingIdentifiers) {
      if (packageName.contains(shadingIdentifier)) {
        return true;
      }
    }
    return false;
  }

  public static ClassNameFiltering allowAll() {
    return new ClassNameFiltering(
        Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
  }
}
