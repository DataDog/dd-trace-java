package com.datadog.appsec.config;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles dynamic instrumentation updates for Supply Chain Analysis (SCA) vulnerability detection.
 *
 * <p>This class receives SCA configuration updates from Remote Config and triggers retransformation
 * of classes that match the instrumentation targets.
 *
 * <p><b>Key design features:</b>
 *
 * <ul>
 *   <li><b>Persistent transformer</b>: Installs the transformer once and keeps it installed. The
 *       transformer uses a Supplier to read the current config dynamically, eliminating timing gaps
 *       where classes loading during transformer reinstallation would miss instrumentation.
 *   <li><b>Automatic instrumentation of new classes</b>: Classes that load after config arrives are
 *       automatically instrumented by the persistent transformer.
 *   <li><b>Smart retransformation</b>: When config updates, only retransforms classes that are
 *       already loaded. New classes will be instrumented automatically when they load.
 * </ul>
 *
 * <p>Thread-safe: Multiple threads can call {@link #onConfigUpdate(AppSecSCAConfig)} concurrently.
 */
public class AppSecSCAInstrumentationUpdater {

  private static final Logger log = LoggerFactory.getLogger(AppSecSCAInstrumentationUpdater.class);

  private final Instrumentation instrumentation;
  private final Lock updateLock = new ReentrantLock();

  private volatile AppSecSCAConfig currentConfig;
  private AppSecSCATransformer currentTransformer;

  public AppSecSCAInstrumentationUpdater(Instrumentation instrumentation) {
    if (instrumentation == null) {
      throw new IllegalArgumentException("Instrumentation cannot be null");
    }
    if (!instrumentation.isRetransformClassesSupported()) {
      throw new IllegalStateException(
          "SCA requires retransformation support, but it's not available in this JVM");
    }
    this.instrumentation = instrumentation;
  }

  /**
   * Called when SCA configuration is updated via Remote Config.
   *
   * @param newConfig the new SCA configuration, or null if config was removed
   */
  public void onConfigUpdate(AppSecSCAConfig newConfig) {
    updateLock.lock();
    try {
      if (newConfig == null) {
        log.debug("SCA config removed, disabling instrumentation");
        removeInstrumentation();
        currentConfig = null;
        return;
      }

      if (newConfig.vulnerabilities == null || newConfig.vulnerabilities.isEmpty()) {
        log.debug("SCA config has no vulnerabilities");
        removeInstrumentation();
        currentConfig = newConfig;
        return;
      }

      log.info(
          "Applying SCA instrumentation for {} vulnerabilities", newConfig.vulnerabilities.size());

      AppSecSCAConfig oldConfig = currentConfig;
      currentConfig = newConfig;

      applyInstrumentation(oldConfig, newConfig);
    } finally {
      updateLock.unlock();
    }
  }

  private void applyInstrumentation(AppSecSCAConfig oldConfig, AppSecSCAConfig newConfig) {
    // Install transformer on first config if not already installed
    if (currentTransformer == null) {
      log.debug("Installing SCA transformer (will use config dynamically)");
      // Transformer uses supplier to get current config - no need to reinstall on updates
      currentTransformer = new AppSecSCATransformer(() -> currentConfig);
      instrumentation.addTransformer(currentTransformer, true);
    }

    // Determine which classes need to be retransformed
    Set<String> newTargetClassNames = extractTargetClassNames(newConfig);
    Set<String> oldTargetClassNames =
        oldConfig != null ? extractTargetClassNames(oldConfig) : new HashSet<>();

    // Compute classes that need retransformation (new targets + removed targets)
    Set<String> classesToRetransform = new HashSet<>();
    classesToRetransform.addAll(newTargetClassNames); // New or updated targets
    classesToRetransform.addAll(oldTargetClassNames); // Old targets (to remove instrumentation)

    if (classesToRetransform.isEmpty()) {
      log.debug("No target classes to retransform");
      return;
    }

    // Find loaded classes that match targets
    List<Class<?>> loadedClassesToRetransform = findLoadedClasses(classesToRetransform);

    if (loadedClassesToRetransform.isEmpty()) {
      log.debug(
          "No loaded classes match SCA targets yet ({} targets total, they may load later)",
          newTargetClassNames.size());
      return;
    }

    // Trigger retransformation for already loaded classes
    log.info(
        "Retransforming {} loaded classes for SCA instrumentation ({} targets total)",
        loadedClassesToRetransform.size(),
        newTargetClassNames.size());
    retransformClasses(loadedClassesToRetransform);
  }

  private Set<String> extractTargetClassNames(AppSecSCAConfig config) {
    Set<String> classNames = new HashSet<>();

    if (config == null || config.vulnerabilities == null) {
      return classNames;
    }

    for (AppSecSCAConfig.Vulnerability vulnerability : config.vulnerabilities) {
      // Extract vulnerable internal code class
      if (vulnerability.vulnerableInternalCode != null
          && vulnerability.vulnerableInternalCode.className != null
          && !vulnerability.vulnerableInternalCode.className.isEmpty()) {
        // className is already in binary format (org.foo.Bar), no conversion needed
        classNames.add(vulnerability.vulnerableInternalCode.className);
      }

      // Extract external entrypoint class
      if (vulnerability.externalEntrypoint != null
          && vulnerability.externalEntrypoint.className != null
          && !vulnerability.externalEntrypoint.className.isEmpty()) {
        // className is already in binary format (org.foo.Bar), no conversion needed
        classNames.add(vulnerability.externalEntrypoint.className);
      }
    }

    return classNames;
  }

  private List<Class<?>> findLoadedClasses(Set<String> targetClassNames) {
    List<Class<?>> matchedClasses = new ArrayList<>();

    Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
    log.debug("Scanning {} loaded classes for SCA targets", loadedClasses.length);

    for (Class<?> clazz : loadedClasses) {
      if (targetClassNames.contains(clazz.getName())) {
        if (!instrumentation.isModifiableClass(clazz)) {
          log.debug("Class {} matches target but is not modifiable", clazz.getName());
          continue;
        }
        matchedClasses.add(clazz);
        log.debug("Found loaded class matching SCA target: {}", clazz.getName());
      }
    }

    return matchedClasses;
  }

  private void retransformClasses(List<Class<?>> classes) {
    for (Class<?> clazz : classes) {
      try {
        log.debug("Retransforming class: {}", clazz.getName());
        instrumentation.retransformClasses(clazz);
      } catch (Exception e) {
        log.error("Failed to retransform class: {}", clazz.getName(), e);
      } catch (Throwable t) {
        log.error("Throwable during retransformation of class: {}", clazz.getName(), t);
      }
    }
  }

  private void removeInstrumentation() {
    if (currentTransformer != null) {
      log.debug("Removing SCA transformer");
      instrumentation.removeTransformer(currentTransformer);
      currentTransformer = null;
    }

    // TODO: Optionally retransform classes to remove instrumentation
    // For now, instrumentation stays until JVM restart
  }

  /**
   * Gets the current SCA configuration.
   *
   * @return the current config, or null if none is active
   */
  public AppSecSCAConfig getCurrentConfig() {
    return currentConfig;
  }

  /** For testing: checks if a transformer is currently installed. */
  boolean hasTransformer() {
    return currentTransformer != null;
  }
}
