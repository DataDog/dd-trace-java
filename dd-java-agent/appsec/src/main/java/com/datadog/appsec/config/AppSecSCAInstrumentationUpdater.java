package com.datadog.appsec.config;

import java.lang.instrument.ClassFileTransformer;
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
 * <p>This class receives SCA configuration updates from Remote Config and triggers
 * retransformation of classes that match the instrumentation targets.
 *
 * <p>Thread-safe: Multiple threads can call {@link #onConfigUpdate(AppSecSCAConfig)} concurrently.
 */
public class AppSecSCAInstrumentationUpdater {

  private static final Logger log = LoggerFactory.getLogger(AppSecSCAInstrumentationUpdater.class);

  private final Instrumentation instrumentation;
  private final Lock updateLock = new ReentrantLock();

  private volatile AppSecSCAConfig currentConfig;
  private ClassFileTransformer currentTransformer;

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

      if (!isEnabled(newConfig)) {
        log.debug("SCA config disabled, removing instrumentation");
        removeInstrumentation();
        currentConfig = newConfig;
        return;
      }

      if (newConfig.instrumentationTargets == null || newConfig.instrumentationTargets.isEmpty()) {
        log.debug("SCA config has no instrumentation targets");
        removeInstrumentation();
        currentConfig = newConfig;
        return;
      }

      log.info(
          "Applying SCA instrumentation for {} targets",
          newConfig.instrumentationTargets.size());

      AppSecSCAConfig oldConfig = currentConfig;
      currentConfig = newConfig;

      applyInstrumentation(oldConfig, newConfig);
    } finally {
      updateLock.unlock();
    }
  }

  private boolean isEnabled(AppSecSCAConfig config) {
    return config.enabled != null && config.enabled;
  }

  private void applyInstrumentation(AppSecSCAConfig oldConfig, AppSecSCAConfig newConfig) {
    // Determine which classes need to be retransformed
    Set<String> targetClassNames = extractTargetClassNames(newConfig);

    if (targetClassNames.isEmpty()) {
      log.debug("No valid target class names found");
      return;
    }

    // Remove old transformer if exists
    if (currentTransformer != null) {
      log.debug("Removing previous SCA transformer");
      instrumentation.removeTransformer(currentTransformer);
      currentTransformer = null;
    }

    // Install new transformer
    log.debug("Installing new SCA transformer for targets: {}", targetClassNames);
    currentTransformer = new AppSecSCATransformer(newConfig);
    instrumentation.addTransformer(currentTransformer, true);

    // Find loaded classes that match targets
    List<Class<?>> classesToRetransform = findLoadedClasses(targetClassNames);

    if (classesToRetransform.isEmpty()) {
      log.debug("No loaded classes match SCA targets (they may load later)");
      return;
    }

    // Trigger retransformation
    log.info("Retransforming {} classes for SCA instrumentation", classesToRetransform.size());
    retransformClasses(classesToRetransform);
  }

  private Set<String> extractTargetClassNames(AppSecSCAConfig config) {
    Set<String> classNames = new HashSet<>();

    for (AppSecSCAConfig.InstrumentationTarget target : config.instrumentationTargets) {
      if (target.className == null || target.className.isEmpty()) {
        log.warn("Skipping target with null or empty className");
        continue;
      }

      // Convert internal format (org/foo/Bar) to binary name (org.foo.Bar)
      String binaryName = target.className.replace('/', '.');
      classNames.add(binaryName);
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

  /**
   * For testing: checks if a transformer is currently installed.
   */
  boolean hasTransformer() {
    return currentTransformer != null;
  }
}
