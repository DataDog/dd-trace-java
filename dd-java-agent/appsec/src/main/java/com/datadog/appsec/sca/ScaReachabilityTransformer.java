package com.datadog.appsec.sca;

import datadog.telemetry.dependency.Dependency;
import datadog.telemetry.dependency.DependencyResolver;
import datadog.trace.api.telemetry.ScaReachabilityCollector;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Observation-only {@link ClassFileTransformer} that detects when classes from vulnerable libraries
 * are loaded and reports reachability hits via {@link ScaReachabilityCollector}.
 *
 * <p>Design principles (see APPSEC-62260 and .claude-invariants.md):
 *
 * <ul>
 *   <li>Always returns {@code null} — never modifies bytecode for class-level symbols.
 *   <li>Never throws — any error in {@link #transform} is caught silently to avoid breaking class
 *       loading.
 *   <li>All shared state uses concurrent collections — {@link #transform} is called from multiple
 *       class-loading threads simultaneously.
 *   <li>Version resolution is cached per JAR URL — each JAR is read at most once.
 *   <li>Each (vulnId, artifact) pair is reported at most once — RFC requires a single occurrence.
 *   <li>Path B (JDK classes such as {@code java.sql.PreparedStatement}) is handled only in {@link
 *       #checkAlreadyLoadedClasses}, not in {@link #transform}, because JDK classes are always
 *       loaded at startup. If a JDK class relevant to a CVE were loaded lazily after startup, the
 *       detection would be missed. This is a known, documented trade-off.
 * </ul>
 */
public final class ScaReachabilityTransformer implements ClassFileTransformer {

  private static final Logger log = LoggerFactory.getLogger(ScaReachabilityTransformer.class);

  private final ScaCveDatabase database;

  /** Cache: JAR URL → resolved dependencies (empty list = JAR has no pom.properties). */
  private final ConcurrentHashMap<URL, List<Dependency>> jarCache = new ConcurrentHashMap<>();

  /** Deduplication set: "vulnId|artifact" pairs already reported. */
  private final Set<String> reportedHits = ConcurrentHashMap.newKeySet();

  public ScaReachabilityTransformer(ScaCveDatabase database) {
    this.database = database;
  }

  // ---------------------------------------------------------------------------
  // ClassFileTransformer
  // ---------------------------------------------------------------------------

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    try {
      // Filter array types (e.g. "[Ljava/sql/PreparedStatement;").
      if (className == null || className.charAt(0) == '[') {
        return null;
      }

      // JDK/bootstrap classes (protectionDomain == null) are handled at startup in
      // checkAlreadyLoadedClasses() via Path B. Skip here to avoid per-bootstrap-class overhead.
      if (protectionDomain == null) {
        return null;
      }

      List<ScaEntry> entries = database.entriesForClass(className);
      if (entries == null || entries.isEmpty()) {
        return null;
      }

      CodeSource codeSource = protectionDomain.getCodeSource();
      if (codeSource == null) {
        return null; // runtime-generated class (dynamic proxy, lambda, etc.)
      }
      URL location = codeSource.getLocation();
      if (location == null) {
        return null;
      }

      processPathA(className, location, entries);
    } catch (Throwable t) {
      // Never propagate from transform() — it would break the class being loaded.
      log.debug("SCA Reachability: error processing class {}", className, t);
    }
    return null; // observation only — never modify bytecode for class-level symbols
  }

  // ---------------------------------------------------------------------------
  // Startup scan for already-loaded classes
  // ---------------------------------------------------------------------------

  /**
   * Checks classes already loaded before this transformer was registered.
   *
   * <p>Path A: 3rd-party classes — version resolved from the class's own JAR via {@link
   * ProtectionDomain}.
   *
   * <p>Path B: JDK/standard-library classes (e.g. {@code java.sql.PreparedStatement}) — {@code
   * ProtectionDomain} is null, so we scan the system classloader's URL chain for the associated
   * Maven artifact.
   *
   * <p><b>Assumption:</b> JDK-sourced symbols in vulnerability data are loaded at startup, not
   * lazily during normal application operation. If an application defers JDK class loading past
   * agent startup (e.g. lazy JDBC initialisation), Path B hits for those classes will be missed.
   * See APPSEC-62260 for design rationale.
   */
  public void checkAlreadyLoadedClasses(Instrumentation instrumentation) {
    for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
      String internalName = clazz.getName().replace('.', '/');
      if (internalName.charAt(0) == '[') {
        continue;
      }
      List<ScaEntry> entries = database.entriesForClass(internalName);
      if (entries == null || entries.isEmpty()) {
        continue;
      }

      ProtectionDomain pd = clazz.getProtectionDomain();
      URL location = locationOf(pd);
      try {
        if (location == null) {
          processPathB(internalName, entries); // JDK class
        } else {
          processPathA(internalName, location, entries); // 3rd-party class
        }
      } catch (Exception e) {
        // Never abort the scan — a failure on one class must not skip the remaining ones.
        log.debug("SCA Reachability: error scanning already-loaded class {}", internalName, e);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Internal matching logic
  // ---------------------------------------------------------------------------

  /** Path A: class came from a 3rd-party JAR — match artifact + check version. */
  private void processPathA(String internalClassName, URL jarUrl, List<ScaEntry> entries) {
    List<Dependency> deps = resolveDependencies(jarUrl);
    for (ScaEntry entry : entries) {
      for (Dependency dep : deps) {
        if (entry.artifact().equals(dep.name) && entry.isVersionVulnerable(dep.version)) {
          reportHit(entry, dep.version, internalClassName);
        }
      }
    }
  }

  /** Path B: class came from the JDK — find the vulnerable artifact in the classloader chain. */
  private void processPathB(String internalClassName, List<ScaEntry> entries) {
    for (ScaEntry entry : entries) {
      String version = findArtifactVersionInClasspath(entry.artifact());
      if (version != null && entry.isVersionVulnerable(version)) {
        reportHit(entry, version, internalClassName);
      }
    }
  }

  // package-private for testing
  String findArtifactVersionInClasspath(String artifactName) {
    Set<URL> scanned = new HashSet<>();

    // Walk URLClassLoader chain (covers Java 8 system classloader and custom classloaders on 9+)
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    while (cl != null) {
      if (cl instanceof URLClassLoader) {
        for (URL url : ((URLClassLoader) cl).getURLs()) {
          if (scanned.add(url)) {
            String version = findArtifactInUrl(artifactName, url);
            if (version != null) {
              return version;
            }
          }
        }
      }
      cl = cl.getParent();
    }

    // Fallback for Java 9+: system classloader (jdk.internal.loader.ClassLoaders$AppClassLoader)
    // no longer extends URLClassLoader, so the loop above misses the main classpath. The
    // java.class.path system property always contains the classpath entries in this case.
    String classpath = System.getProperty("java.class.path", "");
    for (String entry : classpath.split(File.pathSeparator)) {
      if (entry.isEmpty()) {
        continue;
      }
      try {
        URL url = new File(entry).toURI().toURL();
        if (scanned.add(url)) {
          String version = findArtifactInUrl(artifactName, url);
          if (version != null) {
            return version;
          }
        }
      } catch (Exception e) {
        log.debug("SCA Reachability: could not scan classpath entry {}", entry, e);
      }
    }
    return null;
  }

  private String findArtifactInUrl(String artifactName, URL url) {
    for (Dependency dep : resolveDependencies(url)) {
      if (artifactName.equals(dep.name) && dep.version != null) {
        return dep.version;
      }
    }
    return null;
  }

  private void reportHit(ScaEntry entry, String version, String internalClassName) {
    String dedupKey = entry.vulnId() + "|" + entry.artifact();
    if (!reportedHits.add(dedupKey)) {
      return; // already reported this (vulnId, artifact) pair — RFC: single occurrence sufficient
    }
    String dotClassName = internalClassName.replace('/', '.');
    log.debug(
        "SCA Reachability: {} reached in {}:{} via class {}",
        entry.vulnId(),
        entry.artifact(),
        version,
        dotClassName);
    ScaReachabilityCollector.INSTANCE.addHit(
        new ScaReachabilityHit(entry.vulnId(), entry.artifact(), version, dotClassName));
  }

  private List<Dependency> resolveDependencies(URL url) {
    List<Dependency> cached = jarCache.get(url);
    if (cached != null) {
      return cached;
    }
    List<Dependency> resolved;
    try {
      URI uri = url.toURI();
      resolved = DependencyResolver.resolve(uri);
      if (resolved == null) {
        resolved = Collections.emptyList();
      }
    } catch (Exception e) {
      log.debug("SCA Reachability: could not resolve {}", url, e);
      resolved = Collections.emptyList();
    }
    List<Dependency> existing = jarCache.putIfAbsent(url, resolved);
    return existing != null ? existing : resolved;
  }

  private static URL locationOf(ProtectionDomain pd) {
    if (pd == null) return null;
    CodeSource cs = pd.getCodeSource();
    if (cs == null) return null;
    return cs.getLocation();
  }
}
