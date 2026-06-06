package com.datadog.appsec.sca;

import datadog.telemetry.dependency.Dependency;
import datadog.telemetry.dependency.DependencyResolver;
import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import datadog.trace.util.Strings;
import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
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
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Label;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.utility.OpenedClassReader;
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
 *       {@link #transform} only enqueues the event and returns {@code null} — no JAR I/O on the
 *       class-loading thread. {@link #processPendingClassEvents} runs on the telemetry thread each
 *       heartbeat and performs all heavyweight work (JAR reads, version resolution, hit reporting).
 *       Method-level bytecode injection is deferred further to {@link #performPendingRetransforms},
 *       which calls {@link Instrumentation#retransformClasses} and fires {@link #transform} again
 *       with {@code classBeingRedefined != null}.
 *   <li><b>Never throws</b>: any error in {@link #transform} is caught silently to avoid breaking
 *       class loading.
 *   <li><b>Concurrent</b>: all shared state uses concurrent collections — {@link #transform} is
 *       called from multiple class-loading threads simultaneously.
 *   <li><b>Version cache</b>: each JAR is read at most once; non-empty results are cached in {@link
 *       #jarCache}.
 *   <li><b>Single occurrence</b>: each (vulnId, artifact, symbolName) tuple is reported at most
 *       once per RFC requirement. Class-level dedup lives in {@link #reportedHits}; method-level
 *       dedup lives in {@code ScaReachabilityCallback.reported} (bootstrap-side, persists across
 *       retransforms).
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
  private final ConcurrentHashMap<URI, List<Dependency>> jarCache = new ConcurrentHashMap<>();

  /**
   * Cache: artifact name → classpath-resolved version. Used when the class's own JAR does not
   * contain the vulnerable artifact (e.g., Spring Boot starters whose watched classes live in
   * transitive dependency JARs). Only non-null results are cached; null means "not yet found" and
   * will be retried on the next periodic retransform.
   */
  private final ConcurrentHashMap<String, String> classpathArtifactCache =
      new ConcurrentHashMap<>();

  /** Deduplication set: "vulnId|artifact|symbol" tuples already reported. */
  private final Set<String> reportedHits = ConcurrentHashMap.newKeySet();

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
  // package-private for testing
  final ConcurrentLinkedQueue<Class<?>> pendingRetransform = new ConcurrentLinkedQueue<>();

  /** Class names (internal format) queued for deferred retransformation by name lookup. */
  // package-private for testing
  final Set<String> pendingRetransformNames = ConcurrentHashMap.newKeySet();

  /**
   * Queue of classes detected on first load but not yet processed. Populated by {@link #transform}
   * (class-loading thread); drained by {@link #processPendingClassEvents} (telemetry thread).
   */
  // package-private for testing
  final ConcurrentLinkedQueue<PendingClass> pendingClassEvents = new ConcurrentLinkedQueue<>();

  static final class PendingClass {
    final String className;
    final URL jarUrl;

    PendingClass(String className, URL jarUrl) {
      this.className = className;
      this.jarUrl = jarUrl;
    }
  }

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

      if (classBeingRedefined == null) {
        // First load: enqueue for deferred processing on the telemetry thread so that JAR I/O
        // (DependencyResolver.resolve) does not run on the class-loading thread.
        // processPendingClassEvents() will handle resolution, reporting, and scheduling
        // retransformation for method-level symbols on the next telemetry heartbeat.
        pendingClassEvents.add(new PendingClass(className, location));
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
   * #performPendingRetransforms} for classes that have method-level symbols. Class-level hits were
   * already reported by {@link #reportClassLevelHits} during {@link #processPendingClassEvents}.
   *
   * <p>Returns modified bytecode if method-level callbacks were injected, or {@code null} if only
   * class-level symbols were present (no bytecode change needed).
   */
  private byte[] processClass(
      String className, URL jarUrl, List<ScaEntry> entries, byte[] classfileBuffer) {
    List<Dependency> classJarDeps = resolveDependencies(jarUrl);

    // Collect method-level callbacks to inject, keyed by method name
    Map<String, List<MethodCallbackSpec>> methodCallbacks = new HashMap<>();
    boolean hasUnresolvedMethodLevelSymbols = false;
    // Computed lazily: only needed for method-level symbol injection.
    String dotClassName = null;

    for (ScaEntry entry : entries) {
      // Resolve version: first check the class's own JAR, then fall back to a full classpath
      // scan. The fallback handles cases where the vulnerable artifact is an aggregator/starter
      // POM whose watched classes actually live in a transitive dependency JAR (e.g.,
      // spring-boot-starter-web watches @Controller, but @Controller is in spring-context.jar).
      String version = resolveVersionForArtifact(entry.artifact(), classJarDeps);
      if (version == null) {
        // Version not yet resolvable - check lazily (only here) whether this entry has
        // method-level symbols, to decide if a periodic retry should be scheduled.
        // Doing this check only when version==null avoids the stream allocation on the
        // common path where the version resolves successfully.
        if (entry.symbols().stream()
            .anyMatch(s -> s.className().equals(className) && !s.isClassLevel())) {
          hasUnresolvedMethodLevelSymbols = true;
        }
        continue;
      }

      if (!entry.isVersionVulnerable(version)) {
        continue;
      }

      // Report class-level hit immediately; register method-level CVEs and collect for ASM
      // injection.
      reportClassLevelHitIfPresent(entry, version, className);
      for (ScaSymbol symbol : entry.symbols()) {
        if (!symbol.className().equals(className) || symbol.isClassLevel()) {
          continue;
        }
        // Register the CVE now (at class load time) with reached=[] so the next heartbeat
        // signals the backend that SCA is monitoring this CVE. The callsite will be added
        // later when the method is actually called (via ScaReachabilityCallback).
        ScaReachabilityDependencyRegistry.INSTANCE.registerCve(
            entry.artifact(), version, entry.vulnId());
        if (dotClassName == null) {
          dotClassName = Strings.getClassName(className);
        }
        methodCallbacks
            .computeIfAbsent(symbol.method(), k -> new ArrayList<>())
            .add(
                new MethodCallbackSpec(
                    entry.vulnId(), entry.artifact(), version, dotClassName, symbol.method()));
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
    return injectMethodCallbacks(classfileBuffer, methodCallbacks);
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
      if (entries == null || entries.isEmpty()) {
        continue;
      }

      ProtectionDomain pd = clazz.getProtectionDomain();
      URL location = locationOf(pd);
      if (location == null) {
        // JDK/bootstrap class (no code source): skip - false positive, see class Javadoc.
        continue;
      }
      try {
        reportClassLevelHits(internalName, location, entries);
        // If any entry for this class has method-level symbols, the class needs retransformation
        // so the bytecode callback can be injected. We can't modify bytecode here (we're just
        // scanning) - retransformation is deferred to performPendingRetransforms().
        if (hasMethodLevelSymbolForClass(entries, internalName)) {
          pendingRetransform.add(clazz);
        }
      } catch (Exception e) {
        // Never abort the scan - a failure on one class must not skip the remaining ones.
        log.debug("SCA Reachability: error scanning already-loaded class {}", internalName, e);
      }
    }
  }

  /**
   * Processes classes enqueued by {@link #transform} on first load.
   *
   * <p>Runs on the telemetry thread (heartbeat) so that JAR I/O does not block class loading. For
   * each pending class:
   *
   * <ol>
   *   <li>Resolves the JAR dependencies via {@link DependencyResolver} (I/O, cached after first
   *       read per JAR).
   *   <li>Reports class-level hits immediately.
   *   <li>Schedules retransformation for method-level symbols by adding to {@link
   *       #pendingRetransformNames}; {@link #performPendingRetransforms} handles the actual {@link
   *       Instrumentation#retransformClasses} call on the same heartbeat.
   * </ol>
   *
   * <p>Must be called <em>before</em> {@link #performPendingRetransforms} so that classes queued
   * here are retransformed in the same heartbeat.
   */
  public void processPendingClassEvents() {
    PendingClass event;
    while ((event = pendingClassEvents.poll()) != null) {
      final String className = event.className;
      List<ScaEntry> entries = database.entriesForClass(className);
      if (entries == null || entries.isEmpty()) {
        continue;
      }
      try {
        reportClassLevelHits(className, event.jarUrl, entries);
        if (hasMethodLevelSymbolForClass(entries, className)) {
          pendingRetransformNames.add(className);
        }
      } catch (Exception e) {
        log.debug("SCA Reachability: error processing deferred class {}", className, e);
      }
    }
  }

  /**
   * Retransforms classes scheduled for method-level bytecode injection:
   *
   * <ol>
   *   <li>Classes detected on first load and queued by {@link #processPendingClassEvents}.
   *   <li>Classes already loaded before the transformer was registered ({@link
   *       #checkAlreadyLoadedClasses}).
   *   <li>Classes whose JAR version could not be resolved (will be retried).
   * </ol>
   *
   * <p>Must be called <em>after</em> {@link #processPendingClassEvents} so that classes queued in
   * the same heartbeat are retransformed immediately.
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
      toRetransform.add(clazz);
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
          toRetransform.add(loaded);
          matched.add(name);
        }
      }
      pendingRetransformNames.removeAll(matched);
    }

    if (toRetransform.isEmpty()) {
      return;
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

  private void reportClassLevelHits(String internalClassName, URL jarUrl, List<ScaEntry> entries) {
    List<Dependency> classJarDeps = resolveDependencies(jarUrl);
    for (ScaEntry entry : entries) {
      String version = resolveVersionForArtifact(entry.artifact(), classJarDeps);
      if (version == null || !entry.isVersionVulnerable(version)) {
        continue;
      }
      reportClassLevelHitIfPresent(entry, version, internalClassName);
    }
  }

  private static boolean hasMethodLevelSymbolForClass(List<ScaEntry> entries, String className) {
    return entries.stream()
        .flatMap(e -> e.symbols().stream())
        .anyMatch(s -> s.className().equals(className) && !s.isClassLevel());
  }

  /**
   * Reports a class-level reachability hit for the first class-level symbol in {@code entry} that
   * matches {@code internalClassName}. No-op if no matching class-level symbol exists.
   */
  private void reportClassLevelHitIfPresent(
      ScaEntry entry, String version, String internalClassName) {
    for (ScaSymbol symbol : entry.symbols()) {
      if (symbol.className().equals(internalClassName) && symbol.isClassLevel()) {
        reportHit(entry, version, internalClassName, ScaReachabilityHit.CLASS_LEVEL_SYMBOL, 1);
        return; // one hit per entry is sufficient
      }
    }
  }

  /**
   * Resolves the version of {@code artifactName} using a two-step strategy:
   *
   * <ol>
   *   <li>Check the dependencies resolved from the class's own JAR ({@code classJarDeps}). This
   *       covers the common case where the class and its artifact live in the same JAR.
   *   <li>If not found, fall back to a full classpath scan via {@link
   *       #findArtifactVersionInClasspath}. This handles aggregator/starter POM artifacts (e.g.,
   *       {@code spring-boot-starter-web}) whose watched classes live in transitive dependency JARs
   *       rather than in the starter JAR itself. Results of successful scans are cached.
   * </ol>
   *
   * @return the resolved version string, or {@code null} if the artifact cannot be found
   */
  // package-private for testing
  String resolveVersionForArtifact(String artifactName, List<Dependency> classJarDeps) {
    for (Dependency dep : classJarDeps) {
      if (artifactName.equals(dep.name)) {
        return dep.version;
      }
    }
    // Classpath fallback: check cache first, then scan.
    String cached = classpathArtifactCache.get(artifactName);
    if (cached != null) {
      return cached;
    }
    String version = findArtifactVersionInClasspath(artifactName);
    if (version != null) {
      classpathArtifactCache.put(artifactName, version); // only cache hits; misses are retried
    }
    return version;
  }

  // ---------------------------------------------------------------------------
  // Method-level bytecode injection (ASM)
  // ---------------------------------------------------------------------------

  private static final String CALLBACK_OWNER =
      "datadog/trace/bootstrap/appsec/sca/ScaReachabilityCallback";
  private static final String CALLBACK_METHOD = "onMethodHit";
  private static final String CALLBACK_DESC =
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V";

  // package-private for testing
  byte[] injectMethodCallbacks(
      byte[] classfileBuffer, Map<String, List<MethodCallbackSpec>> callbacksPerMethod) {
    ClassReader cr = new ClassReader(classfileBuffer);
    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
    cr.accept(new MethodCallbackInjector(cw, callbacksPerMethod), ClassReader.EXPAND_FRAMES);
    return cw.toByteArray();
  }

  private class MethodCallbackInjector extends ClassVisitor {
    private final Map<String, List<MethodCallbackSpec>> callbacksPerMethod;

    MethodCallbackInjector(
        ClassVisitor cv, Map<String, List<MethodCallbackSpec>> callbacksPerMethod) {
      super(OpenedClassReader.ASM_API, cv);
      this.callbacksPerMethod = callbacksPerMethod;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
      List<MethodCallbackSpec> specs = callbacksPerMethod.get(name);
      if (specs == null || specs.isEmpty()) {
        return mv;
      }
      return new MethodEntryInjector(mv, specs);
    }
  }

  private class MethodEntryInjector extends MethodVisitor {
    private final List<MethodCallbackSpec> specs;
    private boolean injected = false;

    MethodEntryInjector(MethodVisitor mv, List<MethodCallbackSpec> specs) {
      super(OpenedClassReader.ASM_API, mv);
      this.specs = specs;
    }

    @Override
    public void visitLineNumber(int line, Label start) {
      if (!injected) {
        injected = true;
        injectCallbacks(line);
      }
      super.visitLineNumber(line, start);
    }

    @Override
    public void visitInsn(int opcode) {
      ensureInjected();
      super.visitInsn(opcode);
    }

    @Override
    public void visitVarInsn(int opcode, int varIndex) {
      ensureInjected();
      super.visitVarInsn(opcode, varIndex);
    }

    @Override
    public void visitMethodInsn(
        int opcode, String owner, String name, String descriptor, boolean isInterface) {
      ensureInjected();
      super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
    }

    @Override
    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
      ensureInjected();
      super.visitFieldInsn(opcode, owner, name, descriptor);
    }

    private void ensureInjected() {
      if (!injected) {
        injected = true;
        injectCallbacks(1); // no debug info - use line 1 as placeholder
      }
    }

    private void injectCallbacks(int line) {
      // No dedup check here: retransformClasses() always starts from the original class bytes,
      // so the callback must be re-injected on every transformation pass. Deduplication of
      // actual runtime reports is handled by ScaReachabilityCallback.reported (bootstrap-side),
      // which persists across retransformations and prevents duplicate hits regardless of how
      // many times the class is retransformed.
      for (MethodCallbackSpec spec : specs) {
        mv.visitLdcInsn(spec.vulnId);
        mv.visitLdcInsn(spec.artifact);
        mv.visitLdcInsn(spec.version);
        mv.visitLdcInsn(spec.dotClassName);
        mv.visitLdcInsn(spec.methodName);
        mv.visitLdcInsn(line); // LDC handles the full int range; SIPUSH is limited to -32768..32767
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC, CALLBACK_OWNER, CALLBACK_METHOD, CALLBACK_DESC, false);
      }
    }
  }

  /** Immutable spec for a single method-level callback to inject. */
  static final class MethodCallbackSpec {
    final String vulnId;
    final String artifact;
    final String version;
    final String dotClassName;
    final String methodName;

    MethodCallbackSpec(
        String vulnId, String artifact, String version, String dotClassName, String methodName) {
      this.vulnId = vulnId;
      this.artifact = artifact;
      this.version = version;
      this.dotClassName = dotClassName;
      this.methodName = methodName;
    }
  }

  // package-private for testing
  String findArtifactVersionInClasspath(String artifactName) {
    // Use URI (not URL) to avoid DNS lookups in equals/hashCode (DMI_COLLECTION_OF_URLS)
    Set<URI> scanned = new HashSet<>();

    // Walk URLClassLoader chain (covers Java 8 system classloader and custom classloaders on 9+)
    ClassLoader cl = Thread.currentThread().getContextClassLoader();
    while (cl != null) {
      if (cl instanceof URLClassLoader) {
        for (URL url : ((URLClassLoader) cl).getURLs()) {
          try {
            if (scanned.add(url.toURI())) {
              String version = findArtifactInUrl(artifactName, url);
              if (version != null) {
                return version;
              }
            }
          } catch (Exception e) {
            log.debug("SCA Reachability: could not scan classloader URL {}", url, e);
          }
        }
      }
      cl = cl.getParent();
    }

    // Fallback for Java 9+: system classloader (jdk.internal.loader.ClassLoaders$AppClassLoader)
    // no longer extends URLClassLoader, so the loop above misses the main classpath. The
    // java.class.path system property always contains the classpath entries in this case.
    String classpath = System.getProperty("java.class.path", "");
    for (String entry : PATH_SEPARATOR.split(classpath)) {
      if (entry.isEmpty()) {
        continue;
      }
      try {
        URI uri = new File(entry).toURI();
        if (scanned.add(uri)) {
          String version = findArtifactInUrl(artifactName, uri.toURL());
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

  private void reportHit(
      ScaEntry entry, String version, String internalClassName, String symbolName, int line) {
    // Include version: two artifact versions loaded in separate classloaders must produce
    // independent class-level hits.
    String dedupKey = entry.vulnId() + "|" + entry.artifact() + "|" + version + "|" + symbolName;
    if (!reportedHits.add(dedupKey)) {
      return;
    }
    String dotClassName = internalClassName.replace('/', '.');
    log.debug(
        "SCA Reachability: {} reached in {}:{} via {}#{}",
        entry.vulnId(),
        entry.artifact(),
        version,
        dotClassName,
        symbolName);
    // Register with callsite in the stateful registry. For class-level, dotClassName and
    // symbolName ("<clinit>") are used as the callsite - there is no separate "caller" frame.
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        entry.artifact(), version, entry.vulnId(), dotClassName, symbolName, line);
  }

  private List<Dependency> resolveDependencies(URL url) {
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
