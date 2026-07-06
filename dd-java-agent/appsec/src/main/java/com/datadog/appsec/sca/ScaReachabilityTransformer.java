package com.datadog.appsec.sca;

import com.datadog.appsec.sca.ScaMethodCallbackInjector.MethodCallbackSpec;
import datadog.telemetry.dependency.Dependency;
import datadog.telemetry.dependency.DependencyResolver;
import datadog.trace.api.internal.VisibleForTesting;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import datadog.trace.util.Strings;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link ClassFileTransformer} that detects when classes from vulnerable libraries are loaded and
 * reports reachability hits via {@link ScaReachabilityDependencyRegistry}.
 *
 * <p>Design principles (see APPSEC-62260):
 *
 * <ul>
 *   <li><b>Two-phase processing</b>: on first class load ({@code classBeingRedefined == null}),
 *       {@link #transform} adds the class name to {@link #pendingRetransformNames} and returns
 *       {@code null} — no JAR I/O on the class-loading thread. {@link #performPendingRetransforms}
 *       runs on the telemetry thread each heartbeat, calls {@link
 *       Instrumentation#retransformClasses}, and fires {@link #transform} again with {@code
 *       classBeingRedefined != null} to inject method-level callbacks.
 *   <li><b>Never throws</b>: any error in {@link #transform} is caught silently to avoid breaking
 *       class loading.
 *   <li><b>Concurrent</b>: all shared state uses concurrent collections — {@link #transform} is
 *       called from multiple class-loading threads simultaneously.
 *   <li><b>Version cache</b>: each JAR is read at most once; non-empty results are cached in {@link
 *       #jarCache}.
 *   <li><b>Single occurrence</b>: each (vulnId, artifact, symbolName) tuple is reported at most
 *       once per RFC requirement. Dedup lives in {@code ScaReachabilityCallback.reported}
 *       (bootstrap-side, persists across retransforms).
 * </ul>
 */
public final class ScaReachabilityTransformer implements ClassFileTransformer {

  private static final Logger log = LoggerFactory.getLogger(ScaReachabilityTransformer.class);
  private static final Pattern PATH_SEPARATOR = Pattern.compile(Pattern.quote(File.pathSeparator));

  private final ScaCveDatabase database;
  private final Instrumentation instrumentation;

  /**
   * Cache: JAR URI → resolved dependencies. URI is used instead of URL to avoid DNS lookups in
   * equals/hashCode (DMI_COLLECTION_OF_URLS). Only non-empty results are cached to allow retries.
   */
  @VisibleForTesting
  final ConcurrentHashMap<URI, List<Dependency>> jarCache = new ConcurrentHashMap<>();

  /**
   * Cache: artifact name → classpath-resolved {@link Dependency}. Stores the full dependency (name
   * + version) so that the resolved {@code dep.name} — which may be an artifactId-only name like
   * {@code "junrar"} for JARs without pom.properties — is propagated to {@code registerCve} and
   * kept consistent with the name that {@link datadog.telemetry.dependency.DependencyService} will
   * report. Only non-null results are cached; null means "not yet found" and will be retried on the
   * next periodic retransform.
   */
  @VisibleForTesting
  final ConcurrentHashMap<String, Dependency> classpathArtifactCache = new ConcurrentHashMap<>();

  /**
   * Classes whose bytecode needs (re)transformation for method-level symbol injection:
   *
   * <ul>
   *   <li>Classes already loaded at startup before this transformer was registered.
   *   <li>Classes where JAR version resolution returned no results at load time and needs a retry.
   * </ul>
   *
   * Drained and processed by {@link #performPendingRetransforms()} on each telemetry heartbeat.
   */
  @VisibleForTesting
  final ConcurrentLinkedQueue<Class<?>> pendingRetransform = new ConcurrentLinkedQueue<>();

  /** Class names (internal format) queued for deferred retransformation by name lookup. */
  @VisibleForTesting final Set<String> pendingRetransformNames = ConcurrentHashMap.newKeySet();

  public ScaReachabilityTransformer(ScaCveDatabase database, Instrumentation instrumentation) {
    this.database = database;
    this.instrumentation = instrumentation;
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

      // JDK/bootstrap classes (protectionDomain == null) are skipped - they are loaded regardless
      // of which library is present and are not reliable reachability indicators.
      if (protectionDomain == null) {
        return null;
      }

      List<ScaEntry> entries = database.entriesForClass(className);
      if (entries == null) {
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

      if (classBeingRedefined == null) {
        // First load: schedule a retransform for the next telemetry heartbeat so that JAR I/O
        // (DependencyResolver.resolve) does not run on the class-loading thread.
        pendingRetransformNames.add(className);
        return null;
      }

      // Retransform triggered by performPendingRetransforms() after version resolution succeeds:
      // inject method-level callbacks into the bytecode and return the modified bytes.
      return processClass(className, location, entries, classfileBuffer);
    } catch (Throwable t) {
      // Never propagate from transform() - it would break the class being loaded.
      log.debug("SCA Reachability: error processing class {}", className, t);
    }
    return null;
  }

  /**
   * Injects method-level callbacks into the bytecode of a class being retransformed.
   *
   * <p>Called only on retransformation ({@code classBeingRedefined != null}), triggered by {@link
   * #performPendingRetransforms}.
   *
   * <p>Returns modified bytecode if method-level callbacks were injected, or {@code null} if
   * version resolution failed (no bytecode change needed; will be retried on the next heartbeat).
   */
  private byte[] processClass(
      String className, URL jarUrl, List<ScaEntry> entries, byte[] classfileBuffer) {
    // Cache-only: processClass() runs under JVM retransform locks; no fresh JAR I/O here.
    List<Dependency> classJarDeps = resolveDependenciesFromCache(jarUrl);

    // Collect method-level callbacks to inject, keyed by method name
    Map<String, List<MethodCallbackSpec>> methodCallbacks = new HashMap<>();
    boolean hasUnresolvedMethodLevelSymbols = false;
    // Computed lazily: only needed for method-level symbol injection.
    String dotClassName = null;

    for (ScaEntry entry : entries) {
      // Resolve the dependency for this artifact: first check the class's own JAR, then fall back
      // to a full classpath scan. The fallback handles aggregator/starter POM artifacts whose
      // watched classes actually live in a transitive dependency JAR (e.g., spring-boot-starter-web
      // watches @Controller, but @Controller is in spring-context.jar).
      //
      // We use the resolved dep's name (not entry.artifact()) for registerCve and
      // MethodCallbackSpec
      // to ensure that the registry key matches the name that DependencyService will later report.
      // For JARs without pom.properties, DependencyResolver.guessFallbackNoPom produces an
      // artifactId-only name (e.g. "junrar" instead of "com.github.junrar:junrar"). Using
      // entry.artifact() as the registry key would cause a mismatch with DependencyService's name,
      // causing the CVE telemetry event to lose its source/hash or appear under the wrong name.
      Dependency resolvedDep = resolveArtifactDep(entry.artifact(), classJarDeps);
      if (resolvedDep == null) {
        hasUnresolvedMethodLevelSymbols = true;
        continue;
      }
      String version = resolvedDep.version;
      String depName = resolvedDep.name != null ? resolvedDep.name : entry.artifact();

      if (!entry.isVersionVulnerable(version)) {
        continue;
      }

      for (ScaSymbol symbol : entry.symbols()) {
        if (!symbol.className().equals(className)) {
          continue;
        }
        // Register the CVE now (at class load time) with reached=[] so the next heartbeat
        // signals the backend that SCA is monitoring this CVE. The callsite will be added
        // later when the method is actually called (via ScaReachabilityCallback).
        ScaReachabilityDependencyRegistry.INSTANCE.registerCve(depName, version, entry.vulnId());
        if (dotClassName == null) {
          dotClassName = Strings.getClassName(className);
        }
        methodCallbacks
            .computeIfAbsent(symbol.method(), k -> new ArrayList<>())
            .add(
                new MethodCallbackSpec(
                    entry.vulnId(), depName, version, dotClassName, symbol.method()));
      }
    }

    if (hasUnresolvedMethodLevelSymbols) {
      // Schedule retransformation for a later attempt. In transform(), classBeingRedefined is
      // null (first class load), so we don't have a Class<?> handle to put directly into
      // pendingRetransform. Instead we queue the internal class name; performPendingRetransforms()
      // will resolve it back to a Class<?> via instrumentation.getAllLoadedClasses() and
      // retransform.
      pendingRetransformNames.add(className);
    }

    if (methodCallbacks.isEmpty()) {
      return null;
    }
    return ScaMethodCallbackInjector.inject(classfileBuffer, methodCallbacks);
  }

  // ---------------------------------------------------------------------------
  // Startup scan for already-loaded classes
  // ---------------------------------------------------------------------------

  /**
   * Checks classes already loaded before this transformer was registered.
   *
   * <p>Only processes 3rd-party classes (non-null {@link ProtectionDomain} with a code source). JDK
   * classes are skipped: they are always loaded regardless of which library is in the classpath and
   * would produce false positives if used as reachability proxies. See APPSEC-62260.
   */
  public void checkAlreadyLoadedClasses() {
    for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
      if (clazz == null) {
        continue;
      }
      String internalName = clazz.getName().replace('.', '/');
      if (internalName.charAt(0) == '[') {
        continue;
      }
      List<ScaEntry> entries = database.entriesForClass(internalName);
      if (entries == null) {
        continue;
      }

      ProtectionDomain pd = clazz.getProtectionDomain();
      URL location = locationOf(pd);
      if (location == null) {
        // JDK/bootstrap class (no code source): skip - false positive, see class Javadoc.
        continue;
      }
      // All symbols are method-level: always schedule retransformation so the bytecode
      // callback can be injected. We can't modify bytecode during the startup scan; deferred
      // to performPendingRetransforms().
      pendingRetransform.add(clazz);
    }
  }

  /**
   * Retransforms classes scheduled for method-level bytecode injection:
   *
   * <ol>
   *   <li>Classes detected on first load ({@link #transform} adds them to {@link
   *       #pendingRetransformNames}).
   *   <li>Classes already loaded before the transformer was registered ({@link
   *       #checkAlreadyLoadedClasses}).
   *   <li>Classes whose JAR version could not be resolved (will be retried).
   * </ol>
   *
   * <p>Called by {@code ScaReachabilityPeriodicAction} on each telemetry heartbeat via the {@code
   * periodicWorkCallback} registered in {@link ScaReachabilityDependencyRegistry}.
   */
  public void performPendingRetransforms() {
    if (instrumentation == null) {
      return; // no-op when instrumentation is unavailable (e.g. in unit tests)
    }
    // Drain the direct Class<?> queue (from checkAlreadyLoadedClasses)
    List<Class<?>> toRetransform = new ArrayList<>();
    Class<?> clazz;
    while ((clazz = pendingRetransform.poll()) != null) {
      if (instrumentation.isModifiableClass(clazz)) {
        toRetransform.add(clazz);
      }
    }

    // Resolve any classes queued by name (from processClass timing failures).
    // Use contains+removeAll instead of remove inside the loop: the same class may be loaded
    // by multiple classloaders (e.g. Spring Boot LaunchedURLClassLoader creates more than one
    // instance), and we must retransform ALL of them, not just the first one found.
    if (!pendingRetransformNames.isEmpty()) {
      Set<String> matched = new HashSet<>();
      for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
        if (loaded == null) {
          continue;
        }
        String name = loaded.getName().replace('.', '/');
        if (pendingRetransformNames.contains(name)) {
          // Always add to matched to drain the pending set; only retransform if modifiable.
          matched.add(name);
          if (instrumentation.isModifiableClass(loaded)) {
            toRetransform.add(loaded);
          }
        }
      }
      pendingRetransformNames.removeAll(matched);
    }

    if (toRetransform.isEmpty()) {
      return;
    }

    // Pre-warm caches before retransformClasses() acquires JVM locks — no JAR I/O inside the
    // callback.
    // Two paths in processClass() can trigger fresh JAR I/O under locks:
    //   1. resolveDependenciesFromCache() — safe only if jarCache is already populated.
    //   2. resolveArtifactDep() → findArtifactInClasspath() for aggregator artifacts (e.g.,
    //      spring-boot-starter-web) whose pom.properties is not in the class's own JAR.
    //      Without pre-warming classpathArtifactCache, this scans all java.class.path JARs via
    //      resolveDependencies() under JVM locks, reintroducing the snakeyaml deadlock.
    for (Class<?> c : toRetransform) {
      ProtectionDomain pd = c.getProtectionDomain();
      if (pd == null) {
        continue;
      }
      CodeSource cs = pd.getCodeSource();
      if (cs == null) {
        continue;
      }
      URL loc = cs.getLocation();
      if (loc == null) {
        continue;
      }
      resolveDependencies(loc);
      String internalName = c.getName().replace('.', '/');
      List<ScaEntry> dbEntries = database.entriesForClass(internalName);
      if (dbEntries != null) {
        List<Dependency> classJarDeps = resolveDependenciesFromCache(loc);
        for (ScaEntry entry : dbEntries) {
          resolveVersionForArtifact(entry.artifact(), classJarDeps);
        }
      }
    }

    try {
      instrumentation.retransformClasses(toRetransform.toArray(new Class<?>[0]));
      log.debug(
          "SCA Reachability: retransformed {} class(es) for method-level detection",
          toRetransform.size());
    } catch (Throwable t) {
      log.debug("SCA Reachability: retransformClasses failed", t);
      // Re-queue on failure so the next heartbeat can retry
      pendingRetransform.addAll(toRetransform);
    }
  }

  // ---------------------------------------------------------------------------
  // Internal matching logic
  // ---------------------------------------------------------------------------

  /**
   * Resolves the {@link Dependency} for {@code artifactName} (groupId:artifactId format), returning
   * the matched dependency object — which may have an artifactId-only {@code name} for JARs without
   * {@code pom.properties}. Returns {@code null} if the artifact cannot be found.
   *
   * <p>Use this method (not {@link #resolveVersionForArtifact}) whenever the resolved dependency
   * name needs to be propagated (e.g., for {@code registerCve} and {@code MethodCallbackSpec}), so
   * that registry keys stay consistent with the names that {@link
   * datadog.telemetry.dependency.DependencyService} will report.
   */
  @VisibleForTesting
  Dependency resolveArtifactDep(String artifactName, List<Dependency> classJarDeps) {
    Dependency dep = matchDep(artifactName, classJarDeps);
    if (dep != null) {
      return dep;
    }
    // Classpath fallback: check cache first, then scan.
    Dependency cached = classpathArtifactCache.get(artifactName);
    if (cached != null) {
      return cached;
    }
    dep = findArtifactInClasspath(artifactName);
    if (dep != null) {
      classpathArtifactCache.put(artifactName, dep); // only cache hits; misses are retried
    }
    return dep;
  }

  @VisibleForTesting
  String resolveVersionForArtifact(String artifactName, List<Dependency> classJarDeps) {
    Dependency dep = resolveArtifactDep(artifactName, classJarDeps);
    return dep != null ? dep.version : null;
  }

  /**
   * Matches {@code artifactName} (groupId:artifactId format) against a list of dependencies,
   * returning the matched {@link Dependency} object (not just the version).
   *
   * <p>First tries an exact name match. If that fails, falls back to matching by artifact ID only.
   * The fallback handles JARs without {@code pom.properties}: {@code Dependency.guessFallbackNoPom}
   * can only extract the artifact ID from the filename (no group ID), producing names like {@code
   * "junrar"} for {@code com.github.junrar:junrar}. The returned dependency's {@code name} reflects
   * what {@link datadog.telemetry.dependency.DependencyService} will report for that JAR.
   */
  @VisibleForTesting
  static Dependency matchDep(String artifactName, List<Dependency> deps) {
    for (Dependency dep : deps) {
      if (artifactName.equals(dep.name)) {
        return dep;
      }
    }
    int colonIdx = artifactName.lastIndexOf(':');
    if (colonIdx < 0) {
      return null;
    }
    String artifactId = artifactName.substring(colonIdx + 1);
    for (Dependency dep : deps) {
      if (dep.name != null
          && !dep.name.contains(":")
          && artifactId.equals(dep.name)
          && dep.version != null) {
        return dep;
      }
    }
    return null;
  }

  /**
   * Matches {@code artifactName} against a list of dependencies, returning the resolved version
   * string. Delegates to {@link #matchDep} — use that method when the full dependency object is
   * needed.
   */
  @VisibleForTesting
  static String matchVersion(String artifactName, List<Dependency> deps) {
    Dependency dep = matchDep(artifactName, deps);
    return dep != null ? dep.version : null;
  }

  private Dependency findArtifactInClasspath(String artifactName) {
    // Use URI (not URL) to avoid DNS lookups in equals/hashCode (DMI_COLLECTION_OF_URLS)
    Set<URI> scanned = new HashSet<>();

    // AgentThreadFactory sets context classloader to null on agent threads, so a URLClassLoader
    // chain walk would never execute here. Scan java.class.path directly — this covers both Java 8
    // and Java 9+ for standard deployments. OSGi / multi-tenant apps and Spring Boot fat JARs with
    // dynamically loaded bundle classloaders are a known limitation: version resolution for
    // aggregator artifacts may fail in those environments, falling back to retrying on the next
    // heartbeat.
    String classpath = System.getProperty("java.class.path", "");
    for (String entry : PATH_SEPARATOR.split(classpath)) {
      if (entry.isEmpty()) {
        continue;
      }
      try {
        URI uri = new File(entry).toURI();
        if (scanned.add(uri)) {
          Dependency dep = matchDep(artifactName, resolveDependencies(uri.toURL()));
          if (dep != null) {
            return dep;
          }
        }
      } catch (Exception e) {
        log.debug("SCA Reachability: could not scan classpath entry {}", entry, e);
      }
    }
    return null;
  }

  @VisibleForTesting
  String findArtifactVersionInClasspath(String artifactName) {
    Dependency dep = findArtifactInClasspath(artifactName);
    return dep != null ? dep.version : null;
  }

  private List<Dependency> resolveDependenciesFromCache(URL url) {
    try {
      List<Dependency> cached = jarCache.get(url.toURI());
      return cached != null ? cached : Collections.emptyList();
    } catch (Exception e) {
      // URISyntaxException from url.toURI() — should not happen for JAR URLs from protection domain
      log.debug("SCA Reachability: could not read jarCache for {}", url, e);
      return Collections.emptyList();
    }
  }

  @VisibleForTesting
  List<Dependency> resolveDependencies(URL url) {
    try {
      URI uri = url.toURI();
      List<Dependency> cached = jarCache.get(uri);
      if (cached != null) {
        return cached;
      }
      List<Dependency> resolved = DependencyResolver.resolve(uri);
      if (resolved == null) {
        resolved = Collections.emptyList();
      }
      // Only cache non-empty results: empty means the JAR had no pom.properties, which may be
      // a transient failure. Not caching allows the periodic retransform to retry successfully.
      if (!resolved.isEmpty()) {
        List<Dependency> existing = jarCache.putIfAbsent(uri, resolved);
        return existing != null ? existing : resolved;
      }
      return resolved;
    } catch (Exception e) {
      log.debug("SCA Reachability: could not resolve {}", url, e);
      return Collections.emptyList();
    }
  }

  private static URL locationOf(ProtectionDomain pd) {
    if (pd == null) return null;
    CodeSource cs = pd.getCodeSource();
    if (cs == null) return null;
    return cs.getLocation();
  }
}
