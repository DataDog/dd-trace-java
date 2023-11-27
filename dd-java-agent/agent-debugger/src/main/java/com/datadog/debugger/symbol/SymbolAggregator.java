package com.datadog.debugger.symbol;

import static com.datadog.debugger.symbol.JarScanner.trimPrefixes;

import com.datadog.debugger.sink.SymbolSink;
import datadog.trace.util.AgentTaskScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolAggregator {
  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolAggregator.class);
  private static final String CLASS_SUFFIX = ".class";

  private final SymbolSink sink;
  private final int symbolFlushThreshold;
  private final Map<String, Scope> jarScopesByName = new HashMap<>();
  private final AgentTaskScheduler.Scheduled<SymbolAggregator> scheduled;
  private final Object jarScopeLock = new Object();
  private int totalClasses;
  private volatile Set<String> loadedClasses;

  public SymbolAggregator(SymbolSink sink, int symbolFlushThreshold) {
    this.sink = sink;
    this.symbolFlushThreshold = symbolFlushThreshold;
    scheduled =
        AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
            this::flushRemainingScopes, this, 0, 1, TimeUnit.SECONDS);
  }

  public void parseClass(
      String className, byte[] classfileBuffer, ProtectionDomain protectionDomain) {
    try {
      String jarName = "DEFAULT";
      Path jarPath = JarScanner.extractJarPath(protectionDomain);
      if (jarPath != null && Files.exists(jarPath)) {
        LOGGER.debug("jarpath: {}", jarPath);
        jarName = jarPath.toString();
      }
      parseClass(className, classfileBuffer, jarName);
    } catch (Exception ex) {
      LOGGER.debug("Error parsing class: {}", className, ex);
    }
  }

  public void parseClass(String className, byte[] classfileBuffer, String jarName) {
    if (className == null) {
      return;
    }
    className = trimPrefixes(className);
    if (className.endsWith(CLASS_SUFFIX)) {
      className = className.substring(0, className.length() - CLASS_SUFFIX.length());
    }
    Set<String> localLoadedClasses = loadedClasses;
    if (localLoadedClasses != null && !localLoadedClasses.add(className)) {
      // class already loaded and symbol extracted
      return;
    }
    LOGGER.debug("Extracting Symbols from: {}, located in: {}", className, jarName);
    Scope jarScope = SymbolExtractor.extract(classfileBuffer, jarName);
    addJarScope(jarScope, false);
  }

  private void flushRemainingScopes(SymbolAggregator symbolAggregator) {
    synchronized (jarScopeLock) {
      if (jarScopesByName.isEmpty()) {
        return;
      }
      LOGGER.debug("Flush remaining scopes");
      addJarScope(null, true); // force flush remaining scopes
    }
  }

  private void addJarScope(Scope jarScope, boolean forceFlush) {
    List<Scope> scopes = Collections.emptyList();
    synchronized (jarScopeLock) {
      if (jarScope != null) {
        Scope scope = jarScopesByName.get(jarScope.getName());
        if (scope != null) {
          scope.getScopes().addAll(jarScope.getScopes());
        } else {
          jarScopesByName.put(jarScope.getName(), jarScope);
        }
        totalClasses++;
      }
      if (totalClasses >= symbolFlushThreshold || forceFlush) {
        scopes = new ArrayList<>(jarScopesByName.values());
        jarScopesByName.clear();
        totalClasses = 0;
      }
    }
    if (!scopes.isEmpty()) {
      LOGGER.debug("dumping {} jar scopes to sink", scopes.size());
      for (Scope scope : scopes) {
        LOGGER.debug(
            "dumping {} class scopes to sink from scope: {}",
            scope.getScopes().size(),
            scope.getName());
        sink.addScope(scope);
      }
    }
  }

  void loadedClassesProcessStarted() {
    // to avoid duplicate symbol extraction we keep track of loaded classes
    // during the loaded class extraction phase
    loadedClasses = ConcurrentHashMap.newKeySet();
  }

  void loadedClassesProcessEnded() {
    loadedClasses = null;
  }
}
