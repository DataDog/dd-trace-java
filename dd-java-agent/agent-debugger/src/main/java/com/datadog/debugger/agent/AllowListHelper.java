package com.datadog.debugger.agent;

import datadog.trace.util.ClassNameTrie;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper class for handling allowed classes and packages for instrumentation */
public class AllowListHelper {
  private static final Logger log = LoggerFactory.getLogger(AllowListHelper.class);

  private final boolean allowAll;
  private ClassNameTrie packagePrefixTrie;
  private HashSet<String> classes;

  public AllowListHelper(Configuration.FilterList allowList) {
    this.allowAll =
        allowList == null
            || allowList.getClasses().isEmpty() && allowList.getPackagePrefixes().isEmpty();
    if (allowList != null) {
      ClassNameTrie.Builder builder = new ClassNameTrie.Builder();
      allowList.getPackagePrefixes().stream().forEach(s -> builder.put(s + "*", 1));
      this.packagePrefixTrie = builder.buildTrie();
      this.classes = new HashSet<>(allowList.getClasses());
    }
    if (allowAll) {
      log.debug("AllowList: allow all");
    } else {
      log.debug(
          "AllowList - package prefixes: {}, classes: {}",
          allowList.getPackagePrefixes(),
          allowList.getClasses());
    }
  }

  public boolean isAllowed(String typeName) {
    if (allowAll) {
      return true;
    }
    if (packagePrefixTrie.apply(typeName) > 0) {
      return true;
    }
    if (classes.contains(typeName)) {
      return true;
    }
    return false;
  }

  public boolean isAllowAll() {
    return allowAll;
  }
}
