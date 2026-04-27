package com.datadog.appsec.config;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles dynamic instrumentation updates SCA vulnerability detection.
 *
 * <p>This class receives SCA configuration updates from Remote Config and triggers retransformation
 * of classes that match the instrumentation targets.
 */
public class AppSecSCAInstrumentationUpdater {

  private static final Logger log = LoggerFactory.getLogger(AppSecSCAInstrumentationUpdater.class);

  private final Instrumentation instrumentation;

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
   * <p>Updates the current config reference that the persistent transformer reads via supplier. The
   * transformer remains installed and will automatically instrument any new classes that load.
   *
   * @param newConfig the new SCA configuration, or null if config was removed
   */
  public synchronized void onConfigUpdate(AppSecSCAConfig newConfig) {
    AppSecSCAConfig oldConfig = currentConfig;
    currentConfig = newConfig;

    if (newConfig == null) {
      log.debug("SCA config removed, instrumentation will remain until JVM restart");
      return;
    }

    if (newConfig.vulnerabilities == null || newConfig.vulnerabilities.isEmpty()) {
      log.debug("SCA config has no vulnerabilities, instrumentation will remain until JVM restart");
      return;
    }

    log.debug(
        "Applying SCA instrumentation for {} vulnerabilities", newConfig.vulnerabilities.size());

    applyInstrumentation(oldConfig, newConfig);
  }

  private void applyInstrumentation(AppSecSCAConfig oldConfig, AppSecSCAConfig newConfig) {
    // Install transformer on first config if not already installed
    if (currentTransformer == null) {
      log.debug("Installing SCA transformer (will use config dynamically)");
      // Transformer uses supplier to get current config - no need to reinstall on updates
      currentTransformer = new AppSecSCATransformer(() -> currentConfig);
      instrumentation.addTransformer(currentTransformer, true);
    }

    // Determine which classes need to be retransformed (only NEW targets)
    Set<String> newTargetClassNames = extractTargetClassNames(newConfig);
    Set<String> oldTargetClassNames =
        oldConfig != null ? extractTargetClassNames(oldConfig) : new HashSet<>();

    // Only retransform classes for NEW targets (additive-only approach)
    Set<String> classesToRetransform = new HashSet<>(newTargetClassNames);
    classesToRetransform.removeAll(oldTargetClassNames); // Remove already instrumented targets

    if (classesToRetransform.isEmpty()) {
      log.debug("No new target classes to retransform");
      return;
    }

    // Find loaded classes that match NEW targets
    List<Class<?>> loadedClassesToRetransform = findLoadedClasses(classesToRetransform);

    if (loadedClassesToRetransform.isEmpty()) {
      log.debug(
          "No loaded classes match new SCA targets yet ({} new targets, they may load later)",
          classesToRetransform.size());
      return;
    }

    // Trigger retransformation for already loaded classes with NEW targets
    log.info(
        "Retransforming {} loaded classes for {} new SCA targets",
        loadedClassesToRetransform.size(),
        classesToRetransform.size());
    retransformClasses(loadedClassesToRetransform);
  }

  private Set<String> extractTargetClassNames(AppSecSCAConfig config) {
    Set<String> classNames = new HashSet<>();

    if (config == null || config.vulnerabilities == null) {
      return classNames;
    }

    for (AppSecSCAConfig.Vulnerability vulnerability : config.vulnerabilities) {
      // Extract external entrypoint class we decide to instrument only the external entrypoint
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
