package datadog.trace.agent.tooling.muzzle;

import static java.util.Arrays.asList;

import datadog.trace.agent.tooling.AdviceShader;
import datadog.trace.agent.tooling.HelperScanner;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.dynamic.ClassFileLocator;

/**
 * Resolves the helper classes an {@link InstrumenterModule} injects: a module with a manually
 * declared {@code helperClassNames()} list uses it directly, otherwise the helpers inferred from
 * its advice are used (dependency-ordered, build-time-only classes dropped).
 *
 * <p>Used by {@link MuzzleGenerator}, which resolves once per module and emits both outputs from
 * that single crawl: the {@code $Muzzle} side-class (excluding these helpers from the asserted
 * references) and the module's own {@code helperClassNames()} (holding the resolved list).
 */
final class HelperResolver {
  private static final String MUZZLE_REFERENCE_API = "datadog/trace/agent/tooling/muzzle/Reference";

  private HelperResolver() {}

  /** The crawled advice references and the resolved helper set for a module. */
  static final class Result {
    final List<Reference> references;
    final Set<String> adviceClasses;
    final String[] injectedHelpers;

    Result(List<Reference> references, Set<String> adviceClasses, String[] injectedHelpers) {
      this.references = references;
      this.adviceClasses = adviceClasses;
      this.injectedHelpers = injectedHelpers;
    }
  }

  /** Crawls the module's advice once and resolves both its references and injected helpers. */
  static Result resolve(InstrumenterModule module) {
    File sourceRoot = sourceRootFor(module);
    AdviceShader adviceShader = AdviceShader.with(module.adviceShading());

    // Collect the muzzle references from every advice the module defines.
    Set<String> adviceClasses = new HashSet<>();
    List<Reference> allReferences = new ArrayList<>();
    for (Instrumenter instrumenter : module.typeInstrumentations()) {
      if (instrumenter instanceof Instrumenter.HasMethodAdvice) {
        Collections.addAll(
            allReferences,
            generateReferences(
                (Instrumenter.HasMethodAdvice) instrumenter, adviceShader, adviceClasses));
      }
    }
    return new Result(
        allReferences,
        adviceClasses,
        computeInjectedHelpers(module, allReferences, adviceClasses, sourceRoot));
  }

  private static Reference[] generateReferences(
      Instrumenter.HasMethodAdvice instrumenter,
      AdviceShader adviceShader,
      Set<String> allAdviceClasses) {
    // track sources we've generated references from to avoid recursion
    final Set<String> referenceSources = new HashSet<>();
    final Map<String, Reference> references = new LinkedHashMap<>();
    final Set<String> adviceClasses = new HashSet<>();
    instrumenter.methodAdvice(
        (matcher, adviceClass, additionalClasses) -> {
          adviceClasses.add(adviceClass);
          if (additionalClasses != null) {
            adviceClasses.addAll(asList(additionalClasses));
          }
        });
    // remember the advice roots so callers can exclude them from the injected helper set
    allAdviceClasses.addAll(adviceClasses);
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    for (String adviceClass : adviceClasses) {
      if (referenceSources.add(adviceClass)) {
        for (Map.Entry<String, Reference> entry :
            ReferenceCreator.createReferencesFrom(adviceClass, adviceShader, contextClassLoader)
                .entrySet()) {
          Reference toMerge = references.get(entry.getKey());
          if (null == toMerge) {
            references.put(entry.getKey(), entry.getValue());
          } else {
            references.put(entry.getKey(), toMerge.merge(entry.getValue()));
          }
        }
      }
    }
    return references.values().toArray(new Reference[0]);
  }

  /** Resolves the ordered set of helper classes to inject for a module. */
  static String[] computeInjectedHelpers(
      InstrumenterModule module,
      List<Reference> allReferences,
      Set<String> adviceClasses,
      File sourceRoot) {
    // A module that declares its own helper list uses it directly.
    String[] declaredHelpers = module.helperClassNames();
    if (declaredHelpers.length > 0) {
      return declaredHelpers;
    }

    // Otherwise infer them
    HelperClassPredicate helperPredicate =
        new HelperClassPredicate(name -> isOwnOutput(sourceRoot, name));
    Set<String> helpers = new LinkedHashSet<>();
    for (Reference reference : allReferences) {
      if (!adviceClasses.contains(reference.className)
          && helperPredicate.isHelperClass(reference.className)) {
        helpers.add(reference.className);
      }
    }
    for (String helper : new ArrayList<>(helpers)) {
      if (isOwnOutput(sourceRoot, helper)) {
        addNestedClasses(sourceRoot, helper, helpers);
      }
    }
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    String[] orderedHelpers = discoverAndOrderHelpers(helpers, helperPredicate, contextClassLoader);
    ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(contextClassLoader);
    List<String> injectableHelpers = new ArrayList<>(orderedHelpers.length);
    for (String helper : orderedHelpers) {
      if (!isBuildTimeOnly(helper, locator)) {
        injectableHelpers.add(helper);
      }
    }
    return injectableHelpers.toArray(new String[0]);
  }

  /**
   * The subproject's compiled-output root, taken from the loaded module's code source (the
   * classpath entry it was loaded from, i.e. the raw-classes folder).
   */
  private static File sourceRootFor(InstrumenterModule module) {
    CodeSource codeSource = module.getClass().getProtectionDomain().getCodeSource();
    if (codeSource == null || codeSource.getLocation() == null) {
      throw new IllegalStateException(
          "Cannot locate compiled output for " + module.getClass().getName());
    }
    try {
      return new File(codeSource.getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(
          "Cannot resolve compiled output for " + codeSource.getLocation(), e);
    }
  }

  /** {@code true} if the class was compiled from this instrumentation subproject's own output. */
  private static boolean isOwnOutput(File sourceRoot, String className) {
    return new File(sourceRoot, className.replace('.', '/') + ".class").isFile();
  }

  /** Adds the nested classes ({@code Foo$Bar}, {@code Foo$1}, ...) of an ownOutput helper. */
  private static void addNestedClasses(
      File sourceRoot, String className, Set<String> helperClasses) {
    File classFile = new File(sourceRoot, className.replace('.', '/') + ".class");
    File dir = classFile.getParentFile();
    if (dir == null || !dir.isDirectory()) {
      return;
    }
    int lastDot = className.lastIndexOf('.');
    String pkg = lastDot < 0 ? "" : className.substring(0, lastDot + 1);
    String prefix = (lastDot < 0 ? className : className.substring(lastDot + 1)) + "$";
    File[] siblings = dir.listFiles();
    if (siblings == null) {
      return;
    }
    // listFiles() order is filesystem-dependent; sort for reproducible helper ordering.
    Arrays.sort(siblings, Comparator.comparing(File::getName));
    for (File sibling : siblings) {
      String fileName = sibling.getName();
      if (fileName.startsWith(prefix) && fileName.endsWith(".class")) {
        helperClasses.add(pkg + fileName.substring(0, fileName.length() - ".class".length()));
      }
    }
  }

  /**
   * {@code true} if the class uses the muzzle {@link Reference} API (as a {@link ReferenceProvider}
   * or via {@code compileReferences}). This method is used to avoid injecting build-time-only
   * classes.
   */
  static boolean isBuildTimeOnly(String className, ClassFileLocator locator) {
    try {
      ClassFileLocator.Resolution resolution = locator.locate(className);
      if (!resolution.isResolved()) {
        return false;
      }
      // The muzzle type appears as a constant-pool entry when the class references it.
      return new String(resolution.resolve(), StandardCharsets.ISO_8859_1)
          .contains(MUZZLE_REFERENCE_API);
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Expands the given helpers with any helper classes they depend on and returns them in
   * dependency-first load order (required by {@link datadog.trace.agent.tooling.HelperInjector})
   * via {@link HelperScanner}. Library classes the scanner pulls in are dropped, but helpers that
   * could not be located are kept (appended, unordered).
   */
  private static String[] discoverAndOrderHelpers(
      Set<String> initialHelpers, HelperClassPredicate helperPredicate, ClassLoader loader) {
    if (initialHelpers.isEmpty()) {
      return new String[0];
    }
    List<String> ordered = new ArrayList<>();
    try {
      for (String name :
          HelperScanner.withClassDependencies(
              ClassFileLocator.ForClassLoader.of(loader), initialHelpers.toArray(new String[0]))) {
        if (helperPredicate.isHelperClass(name) && !ordered.contains(name)) {
          ordered.add(name);
        }
      }
    } catch (Throwable ignore) {
      // best-effort ordering; unlocatable helpers are appended below
    }
    for (String helper : initialHelpers) {
      if (!ordered.contains(helper)) {
        ordered.add(helper);
      }
    }
    return ordered.toArray(new String[0]);
  }
}
