package com.datadog.appsec.sca;

import datadog.trace.api.telemetry.ScaReachabilityCollector;
import datadog.trace.api.telemetry.ScaReachabilityHit;
import datadog.trace.bootstrap.appsec.sca.ScaReachabilityCallback;
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
   * Walks the current thread stack to find the first application frame that called the vulnerable
   * method. Uses {@link AbstractStackWalker#isNotDatadogTraceStackElement} (backed by the IAST
   * exclusion trie) to distinguish application code from agent/JDK/framework frames.
   *
   * <p>The stack at call time is:
   *
   * <pre>
   *   ScaReachabilitySystem handler lambda  (skip — agent)
   *   ScaReachabilityCallback.onMethodHit   (skip — agent)
   *   &lt;vulnerableClass&gt;.&lt;method&gt;           (skip — the instrumented library class)
   *   &lt;application callsite&gt;               ← return this
   * </pre>
   *
   * @param vulnerableClass dot-notation FQN of the instrumented class (used to skip library frames)
   * @return first application callsite frame, or {@code null} if not found
   */
  static StackTraceElement findCallsite(String vulnerableClass) {
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    boolean pastVulnerableClass = false;

    for (StackTraceElement frame : stack) {
      String cls = frame.getClassName();

      // Skip agent and JDK frames using the same predicate as AbstractStackWalker
      // (isNotDatadogTraceStackElement is package-private so we replicate the 3 conditions).
      if (cls.startsWith("datadog.trace.")
          || cls.startsWith("com.datadog.iast.")
          || cls.startsWith("com.datadog.appsec.")
          || cls.startsWith("java.")
          || cls.startsWith("javax.")
          || cls.startsWith("sun.")
          || cls.startsWith("jdk.")
          || cls.startsWith("com.sun.")) {
        continue;
      }

      if (!pastVulnerableClass) {
        // Skip frames until we have passed all frames from the vulnerable class
        if (cls.equals(vulnerableClass)) {
          pastVulnerableClass = true;
        }
        continue;
      }

      // Skip any additional frames still inside the vulnerable class (library-internal chains)
      if (cls.equals(vulnerableClass)) {
        continue;
      }

      return frame;
    }
    return null;
  }

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

    // Register the method-level callback. When called synchronously from the injected bytecode,
    // the current thread stack still contains the full call chain:
    //   this handler lambda
    //   ScaReachabilityCallback.onMethodHit
    //   <vulnerable method> (dotClassName.methodName)
    //   <application callsite>  ← what we report
    // We use the IAST trie-based filter (AbstractStackWalker.isNotDatadogTraceStackElement) to
    // identify application frames, matching the existing callsite-detection infrastructure.
    ScaReachabilityCallback.register(
        (vulnId, artifact, version, dotClassName, methodName, line) -> {
          StackTraceElement callsite = findCallsite(dotClassName);
          if (callsite != null) {
            ScaReachabilityCollector.INSTANCE.addHit(
                new ScaReachabilityHit(
                    vulnId,
                    artifact,
                    version,
                    callsite.getClassName(),
                    callsite.getMethodName(),
                    callsite.getLineNumber()));
          } else {
            // Fallback: no application frame found — report the vulnerable symbol so the
            // backend at least knows the method was reached.
            ScaReachabilityCollector.INSTANCE.addHit(
                new ScaReachabilityHit(vulnId, artifact, version, dotClassName, methodName, line));
          }
        });

    ScaReachabilityTransformer transformer =
        new ScaReachabilityTransformer(database, instrumentation);

    // canRetransform=true is required so that future method-level symbols (when added to the
    // database) can trigger retransformation of already-loaded classes via retransformClasses().
    // For current class-level symbols, retransformation is not used — see
    // checkAlreadyLoadedClasses.
    instrumentation.addTransformer(transformer, true);

    transformer.checkAlreadyLoadedClasses(instrumentation);
    log.debug("SCA Reachability: startup scan complete");

    // Register the periodic retransform callback so the telemetry heartbeat can retry
    // method-level instrumentation for classes that could not be processed at load time.
    ScaReachabilityCollector.INSTANCE.setPeriodicWorkCallback(
        transformer::performPendingRetransforms);
  }
}
