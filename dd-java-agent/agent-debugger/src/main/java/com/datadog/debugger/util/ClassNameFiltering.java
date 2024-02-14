package com.datadog.debugger.util;

import datadog.trace.util.ClassNameTrie;
import java.util.Arrays;
import java.util.List;

/** A class to filter out classes based on their package name. */
public class ClassNameFiltering {
  // Hardcode filtering out JDK classes
  private static final List<String> JDK_FILTER_OUT_PACKAGES =
      Arrays.asList("java.", "javax.", "sun.", "com.sun.", "jdk.");
  private final ClassNameTrie trie;

  public ClassNameFiltering(List<String> packages) {
    ClassNameTrie.Builder builder = new ClassNameTrie.Builder();
    JDK_FILTER_OUT_PACKAGES.forEach(s -> builder.put(s + "*", 1));
    packages.forEach(s -> builder.put(s + "*", 1));
    this.trie = builder.buildTrie();
  }

  public boolean apply(String className) {
    return trie.apply(className) > 0;
  }
}
