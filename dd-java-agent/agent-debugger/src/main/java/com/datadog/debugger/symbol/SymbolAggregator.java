package com.datadog.debugger.symbol;

import static com.datadog.debugger.symbol.JarScanner.trimPrefixes;

import com.datadog.debugger.sink.SymbolSink;
import datadog.instrument.utils.ClassNameTrie;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.util.AgentTaskScheduler;
import datadog.trace.util.Strings;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SymbolAggregator {
  private static final Logger LOGGER = LoggerFactory.getLogger(SymbolAggregator.class);
  private static final String CLASS_SUFFIX = ".class";
  private static final int READ_BUFFER_SIZE = 4096;
  private static final int CLASSFILE_BUFFER_SIZE = 8192;

  private final DebuggerContext.ClassNameFilter classNameFilter;
  private final List<ScopeFilter> scopeFilters;
  private final SymbolSink sink;
  private final int symbolFlushThreshold;
  private final Map<String, Scope> jarScopesByName = new HashMap<>();
  private final Object jarScopeLock = new Object();
  private AgentTaskScheduler.Scheduled<SymbolAggregator> flushRemainingScopeScheduled;
  private AgentTaskScheduler.Scheduled<SymbolAggregator> scanJarsScheduled;
  private int totalClasses;
  // ClassNameTrie is not thread safe, All accesses must be protected by a lock
  private final ClassNameTrie.Builder loadedClasses = new ClassNameTrie.Builder();
  private final Queue<String> jarsToScanQueue = new ArrayBlockingQueue<>(128);
  private final Set<String> alreadyScannedJars = ConcurrentHashMap.newKeySet();

  public SymbolAggregator(
      DebuggerContext.ClassNameFilter classNameFilter,
      List<ScopeFilter> scopeFilters,
      SymbolSink sink,
      int symbolFlushThreshold) {
    this.classNameFilter = classNameFilter;
    this.scopeFilters = scopeFilters;
    this.sink = sink;
    this.symbolFlushThreshold = symbolFlushThreshold;
  }

  public void start() {
    flushRemainingScopeScheduled =
        AgentTaskScheduler.get()
            .scheduleAtFixedRate(this::flushRemainingScopes, this, 0, 1, TimeUnit.SECONDS);
    scanJarsScheduled =
        AgentTaskScheduler.get()
            .scheduleAtFixedRate(this::scanQueuedJars, this, 0, 1, TimeUnit.SECONDS);
  }

  public void stop() {
    cancelSchedule(flushRemainingScopeScheduled);
    cancelSchedule(scanJarsScheduled);
  }

  private void cancelSchedule(AgentTaskScheduler.Scheduled<SymbolAggregator> scheduled) {
    if (scheduled != null) {
      scheduled.cancel();
    }
  }

  public void parseClass(
      String className, byte[] classfileBuffer, ProtectionDomain protectionDomain) {
    try {
      String jarName = "DEFAULT";
      Path jarPath = JarScanner.extractJarPath(protectionDomain, SymDBReport.NO_OP);
      if (jarPath != null && Files.exists(jarPath)) {
        LOGGER.debug("jarpath: {}", jarPath);
        jarName = jarPath.toString();
        if (!alreadyScannedJars.contains(jarName)) { // filter out already scanned jars
          if (!jarsToScanQueue.contains(jarName)) { // filter out already queued jars
            LOGGER.debug("Queuing jar to scan: {}", jarPath);
            if (!jarsToScanQueue.offer(jarName)) {
              LOGGER.debug("jarToScan queue is full, skipping jar: {}", jarName);
            }
          }
        }
      }
      parseClass(SymDBReport.NO_OP, className, classfileBuffer, jarName);
    } catch (Exception ex) {
      LOGGER.debug("Error parsing class: {}", className, ex);
    }
  }

  public void parseClass(
      SymDBReport symDBReport, String className, byte[] classfileBuffer, String jarName) {
    if (className == null) {
      return;
    }
    className = trimPrefixes(className);
    if (className.endsWith(CLASS_SUFFIX)) {
      className = className.substring(0, className.length() - CLASS_SUFFIX.length());
    }
    synchronized (loadedClasses) {
      String fqn = Strings.getClassName(className); // ClassNameTrie expects Java class names ('.')
      if (loadedClasses.apply(fqn) > 0) {
        // class already loaded and symbol extracted
        return;
      }
      loadedClasses.put(fqn, 1);
    }
    LOGGER.debug("Extracting Symbols from: {}, located in: {}", className, jarName);
    Scope jarScope = SymbolExtractor.extract(classfileBuffer, jarName);
    jarScope = applyFilters(jarScope);
    addJarScope(jarScope, false);
    symDBReport.incClassCount(jarName);
  }

  private Scope applyFilters(Scope jarScope) {
    for (ScopeFilter filter : scopeFilters) {
      jarScope.getScopes().removeIf(filter::filterOut);
    }
    return jarScope;
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

  void scanQueuedJars(SymbolAggregator symbolAggregator) {
    if (jarsToScanQueue.isEmpty()) {
      return;
    }
    byte[] buffer = new byte[READ_BUFFER_SIZE];
    ByteArrayOutputStream baos = new ByteArrayOutputStream(CLASSFILE_BUFFER_SIZE);
    while (!jarsToScanQueue.isEmpty()) {
      String jarPath = jarsToScanQueue.poll();
      LOGGER.debug("Scanning queued jar: {}", jarPath);
      scanJar(SymDBReport.NO_OP, Paths.get(jarPath), baos, buffer);
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

  public void scanJar(
      SymDBReport symDBReport, Path jarPath, ByteArrayOutputStream baos, byte[] buffer) {
    if (alreadyScannedJars.contains(jarPath.toString())) {
      return;
    }
    File jarPathFile = jarPath.toFile();
    if (jarPathFile.isDirectory()) {
      scanDirectory(jarPath, alreadyScannedJars, baos, buffer, symDBReport);
    } else {
      try {
        try (JarFile jarFile = new JarFile(jarPathFile)) {
          jarFile.stream()
              .filter(jarEntry -> jarEntry.getName().endsWith(".class"))
              .filter(
                  jarEntry ->
                      !classNameFilter.isExcluded(
                          Strings.getClassName(trimPrefixes(jarEntry.getName()))))
              .forEach(
                  jarEntry -> parseJarEntry(symDBReport, jarEntry, jarFile, jarPath, baos, buffer));
        }
      } catch (IOException e) {
        symDBReport.addIOException(jarPath.toString(), e);
        throw new RuntimeException(e);
      }
    }
    symDBReport.addScannedJar(jarPath.toString());
    alreadyScannedJars.add(jarPath.toString());
  }

  private void scanDirectory(
      Path jarPath,
      Set<String> alreadyScannedJars,
      ByteArrayOutputStream baos,
      byte[] buffer,
      SymDBReport symDBReport) {
    try {
      Files.walk(jarPath)
          // explicitly no follow links walking the directory to avoid cycles
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> path.toString().endsWith(".class"))
          .filter(
              path ->
                  !classNameFilter.isExcluded(
                      Strings.getClassName(trimPrefixes(jarPath.relativize(path).toString()))))
          .forEach(path -> parseFileEntry(symDBReport, path, jarPath, baos, buffer));
      alreadyScannedJars.add(jarPath.toString());
    } catch (IOException e) {
      symDBReport.addIOException(jarPath.toString(), e);
      LOGGER.debug("Exception during scanning directory: {}", jarPath, e);
    }
  }

  private void parseFileEntry(
      SymDBReport symDBReport, Path path, Path jarPath, ByteArrayOutputStream baos, byte[] buffer) {
    LOGGER.debug("parsing file class: {}", path.toString());
    try {
      try (InputStream inputStream = Files.newInputStream(path)) {
        int readBytes;
        baos.reset();
        while ((readBytes = inputStream.read(buffer)) != -1) {
          baos.write(buffer, 0, readBytes);
        }
        parseClass(
            symDBReport, path.getFileName().toString(), baos.toByteArray(), jarPath.toString());
      }
    } catch (IOException ex) {
      symDBReport.addIOException(jarPath.toString(), ex);
      LOGGER.debug("Exception during parsing file class: {}", path, ex);
    }
  }

  private void parseJarEntry(
      SymDBReport symDBReport,
      JarEntry jarEntry,
      JarFile jarFile,
      Path jarPath,
      ByteArrayOutputStream baos,
      byte[] buffer) {
    LOGGER.debug("parsing jarEntry class: {}", jarEntry.getName());
    try {
      InputStream inputStream = jarFile.getInputStream(jarEntry);
      int readBytes;
      baos.reset();
      while ((readBytes = inputStream.read(buffer)) != -1) {
        baos.write(buffer, 0, readBytes);
      }
      parseClass(symDBReport, jarEntry.getName(), baos.toByteArray(), jarPath.toString());
    } catch (IOException ex) {
      symDBReport.addIOException(jarPath.toString(), ex);
      LOGGER.debug("Exception during parsing jarEntry class: {}", jarEntry.getName(), ex);
    }
  }
}
