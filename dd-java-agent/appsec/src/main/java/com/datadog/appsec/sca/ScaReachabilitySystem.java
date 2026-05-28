package com.datadog.appsec.sca;

import datadog.trace.api.telemetry.ScaReachabilityDependencyRegistry;
import datadog.trace.bootstrap.appsec.sca.ScaReachabilityCallback;
import datadog.trace.util.stacktrace.AbstractStackWalker;
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
   * <p>Called by reflection from {@code Agent.maybeStartScaReachability()} - the method signature
   * must remain {@code public static void start(Instrumentation)}.
   */
  public static void start(Instrumentation instrumentation) {
    ScaCveDatabase database = ScaCveDatabase.load();
    if (database.isEmpty()) {
      log.info("SCA Reachability: no vulnerability data found - subsystem inactive");
      return;
    }
    log.info("SCA Reachability: loaded {} vulnerable class symbols", database.size());

    // Register the method-level callback. When called synchronously from the injected bytecode,
    // the current thread stack still contains the full call chain:
    //   this handler lambda
    //   ScaReachabilityCallback.onMethodHit
    //   <vulnerable method> (dotClassName.methodName)
    //   [optional intermediate library frames]
    //   <application callsite>  ← what we report
    // Agent frames are filtered by AbstractStackWalker.isNotDatadogTraceStackElement; intermediate
    // library frames are filtered by ScaStackExclusionTrie so we skip past them to client code.
    ScaReachabilityCallback.register(
        (vulnId, artifact, version, dotClassName, methodName, line) -> {
          StackTraceElement callsite = findCallsite(dotClassName);
          if (callsite != null) {
            ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
                artifact,
                version,
                vulnId,
                callsite.getClassName(),
                callsite.getMethodName(),
                callsite.getLineNumber());
          } else {
            // Fallback: no application frame found - report the vulnerable symbol so the
            // backend at least knows the method was reached.
            ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
                artifact, version, vulnId, dotClassName, methodName, line);
          }
        });

    ScaReachabilityTransformer transformer =
        new ScaReachabilityTransformer(database, instrumentation);

    // canRetransform=true is required so that future method-level symbols (when added to the
    // database) can trigger retransformation of already-loaded classes via retransformClasses().
    // For current class-level symbols, retransformation is not used - see
    // checkAlreadyLoadedClasses.
    instrumentation.addTransformer(transformer, true);

    transformer.checkAlreadyLoadedClasses();
    log.debug("SCA Reachability: startup scan complete");

    // processPendingClassEvents drains the first-load queue (JAR resolution + hit reporting);
    // performPendingRetransforms then injects method-level callbacks for any classes it queued.
    // Order matters: process events first so retransforms happen in the same heartbeat.
    ScaReachabilityDependencyRegistry.INSTANCE.setPeriodicWorkCallback(
        () -> {
          transformer.processPendingClassEvents();
          transformer.performPendingRetransforms();
        });
  }

  /**
   * Walks the current thread stack to find the first application frame that called the vulnerable
   * method. Agent frames are skipped via {@link AbstractStackWalker#isNotDatadogTraceStackElement};
   * intermediate library frames (e.g. a wrapper around the vulnerable API) are skipped via {@link
   * ScaStackExclusionTrie}.
   *
   * <p>The stack at call time is:
   *
   * <pre>
   *   ScaReachabilitySystem handler lambda  (skip - agent)
   *   ScaReachabilityCallback.onMethodHit   (skip - agent)
   *   &lt;vulnerableClass&gt;.&lt;method&gt;           (skip - the instrumented library class)
   *   [intermediate library frames]         (skip - trie-excluded)
   *   &lt;application callsite&gt;               ← return this
   * </pre>
   *
   * @param vulnerableClass dot-notation FQN of the instrumented class
   * @return first application callsite frame, or {@code null} if not found
   */
  static StackTraceElement findCallsite(String vulnerableClass) {
    return findCallsite(vulnerableClass, Thread.currentThread().getStackTrace());
  }

  /**
   * Overload that accepts an explicit stack for testing.
   *
   * @see #findCallsite(String)
   */
  static StackTraceElement findCallsite(String vulnerableClass, StackTraceElement[] stack) {
    boolean pastVulnerableClass = false;

    for (StackTraceElement frame : stack) {
      String cls = frame.getClassName();

      // Skip agent frames (datadog.trace.*, com.datadog.appsec.*, etc.)
      if (!AbstractStackWalker.isNotDatadogTraceStackElement(frame)) {
        continue;
      }

      if (!pastVulnerableClass) {
        if (cls.equals(vulnerableClass)) {
          pastVulnerableClass = true;
        }
        continue;
      }

      // Skip remaining frames from the vulnerable class itself
      if (cls.equals(vulnerableClass)) {
        continue;
      }

      // Skip intermediate library frames so we report client code, not a wrapper library
      if (ScaStackExclusionTrie.apply(cls) >= 1) {
        continue;
      }

      return frame;
    }
    return null;
  }
}
