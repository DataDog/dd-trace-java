package com.datadog.debugger.agent;

import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.util.ClassNameTrie;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

/** Helper class for handling denied classes and packages for instrumentation */
public class DenyListHelper implements DebuggerContext.ClassFilter {

  private static final Collection<String> DENIED_PACKAGES =
      Arrays.asList("java.security", "javax.security", "sun.security");
  private static final Collection<String> DENIED_CLASSES =
      Arrays.asList("java.lang.Object", "java.lang.String");

  private ClassNameTrie packagePrefixTrie;
  private HashSet<String> classes;

  public DenyListHelper(Configuration.FilterList denyList) {
    Collection<String> packages = new ArrayList<>(DENIED_PACKAGES);
    Collection<String> classes = new ArrayList<>(DENIED_CLASSES);
    if (denyList != null) {
      packages.addAll(denyList.getPackagePrefixes());
      classes.addAll(denyList.getClasses());
    }
    ClassNameTrie.Builder builder = new ClassNameTrie.Builder();
    packages.stream().forEach(s -> builder.put(s + "*", 1));
    this.packagePrefixTrie = builder.buildTrie();
    this.classes = new HashSet<>(classes);
  }

  @Override
  public boolean isDenied(String fullyQualifiedClassName) {
    if (fullyQualifiedClassName == null) {
      return false;
    }
    int idx = fullyQualifiedClassName.lastIndexOf('.');
    if (idx == -1) {
      // not a fully qualified name or no package
      return false;
    }
    String packageName = fullyQualifiedClassName.substring(0, idx);
    if (packagePrefixTrie.apply(packageName) > 0) {
      return true;
    }
    return classes.contains(fullyQualifiedClassName);
  }
}
