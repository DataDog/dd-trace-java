package com.datadog.appsec.sca;

import java.lang.instrument.Instrumentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for the SCA Reachability subsystem. Called from {@code Agent.java} via reflection
 * (same pattern as {@code AppSecSystem} and {@code IastSystem}).
 *
 * <p>Responsibilities:
 *
 * <ol>
 *   <li>Load {@code sca_cves.json} from the classpath.
 *   <li>Build the class-name index.
 *   <li>Register {@link ScaReachabilityTransformer} with the JVM.
 *   <li>Scan already-loaded classes so that libraries loaded before agent startup are detected.
 * </ol>
 */
public final class ScaReachabilitySystem {

  private static final Logger log = LoggerFactory.getLogger(ScaReachabilitySystem.class);

  private ScaReachabilitySystem() {}

  /**
   * Starts the SCA Reachability subsystem.
   *
   * <p>Called by reflection from {@code Agent.maybeStartScaReachability()} — the method signature
   * must remain {@code public static void start(Instrumentation)}.
   */
  public static void start(Instrumentation instrumentation) {
    ScaCveDatabase database = ScaCveDatabase.load();
    if (database.isEmpty()) {
      log.info("SCA Reachability: no vulnerability data found — subsystem inactive");
      return;
    }
    log.info("SCA Reachability: loaded {} vulnerable class symbols", database.size());

    ScaReachabilityTransformer transformer = new ScaReachabilityTransformer(database);

    // canRetransform=true is required so that future method-level symbols (when added to the
    // database) can trigger retransformation of already-loaded classes via retransformClasses().
    // For current class-level symbols, retransformation is not used — see
    // checkAlreadyLoadedClasses.
    instrumentation.addTransformer(transformer, true);

    transformer.checkAlreadyLoadedClasses(instrumentation);
    log.debug("SCA Reachability: startup scan complete");
  }
}
