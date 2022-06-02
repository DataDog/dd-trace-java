package com.datadog.debugger.agent;

import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helper class for handling allowed classes and packages for instrumentation */
public class AllowListHelper {
  private static final Logger log = LoggerFactory.getLogger(AllowListHelper.class);

  private final boolean allowAll;
  private Trie packagePrefixTrie;
  private HashSet<String> classes;

  public AllowListHelper(Configuration.FilterList allowList) {
    this.allowAll =
        allowList == null
            || allowList.getClasses().isEmpty() && allowList.getPackagePrefixes().isEmpty();
    if (allowList != null) {
      this.packagePrefixTrie = new Trie(allowList.getPackagePrefixes());
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
    if (packagePrefixTrie.hasMatchingPrefix(typeName)) {
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
