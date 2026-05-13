package datadog.trace.bootstrap.appsec.sca;

/**
 * Bootstrap-classloader callback for SCA Reachability method-level detection.
 *
 * <p>Bytecode injected into application classes by {@code ScaReachabilityTransformer} calls {@link
 * #onMethodHit} statically. Because this class lives in the bootstrap classloader, it is visible
 * from any application class regardless of classloader hierarchy.
 *
 * <p>The actual handler is registered at agent startup by {@code ScaReachabilitySystem.start()}.
 */
public final class ScaReachabilityCallback {

  /** Receives method-level reachability hits from instrumented application code. */
  public interface Handler {
    void onMethodHit(
        String vulnId,
        String artifact,
        String version,
        String dotClassName,
        String methodName,
        int line);
  }

  private static volatile Handler handler;

  /** Runtime dedup: "vulnId|artifact|methodName" tuples already reported. */
  private static final java.util.Set<String> reported =
      java.util.Collections.newSetFromMap(
          new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

  /**
   * Called by {@code ScaReachabilitySystem} to wire up the real reporting implementation. Passing
   * {@code null} clears both the handler and the dedup set (used in tests).
   */
  public static void register(Handler h) {
    handler = h;
    if (h == null) {
      reported.clear();
    }
  }

  /**
   * Called from bytecode injected into the entry point of a vulnerable method. Deduplicates at
   * runtime so the handler is called at most once per (vulnId, artifact, methodName) triple.
   *
   * <p>The {@code dotClassName} and {@code methodName} parameters identify the VULNERABLE SYMBOL
   * (baked in at transform time) and are used only for deduplication. The telemetry payload reports
   * the CALLSITE — the application frame that invoked the vulnerable method — by walking the
   * current thread stack, matching what the Python tracer does.
   */
  public static void onMethodHit(
      String vulnId,
      String artifact,
      String version,
      String dotClassName,
      String methodName,
      int line) {
    Handler h = handler;
    if (h == null) {
      return;
    }
    String key = vulnId + "|" + artifact + "|" + methodName;
    if (reported.add(key)) {
      // Find the callsite: the first application frame above the vulnerable method.
      // The stack at this point is:
      //   ScaReachabilityCallback.onMethodHit  (us)
      //   <dotClassName>.<methodName>           (vulnerable method, skip)
      //   <application frame>                  (callsite we want to report)
      StackTraceElement caller = findCallerFrame(dotClassName);
      if (caller != null) {
        h.onMethodHit(
            vulnId,
            artifact,
            version,
            caller.getClassName(),
            caller.getMethodName(),
            caller.getLineNumber());
      } else {
        // Fallback: no application frame found (e.g. called directly from JDK internals).
        // Report the vulnerable symbol itself so the backend at least knows it was reached.
        h.onMethodHit(vulnId, artifact, version, dotClassName, methodName, line);
      }
    }
  }

  /**
   * Walks the current thread stack to find the first application frame that called the vulnerable
   * method. Skips the callback frame itself, the vulnerable class frame(s), and any JDK or agent
   * frames.
   *
   * @param vulnerableClass dot-notation FQN of the instrumented class
   * @return the callsite frame, or {@code null} if no application frame is found
   */
  private static StackTraceElement findCallerFrame(String vulnerableClass) {
    StackTraceElement[] stack = Thread.currentThread().getStackTrace();
    boolean pastCallback = false;
    boolean pastVulnerableClass = false;

    for (StackTraceElement frame : stack) {
      String cls = frame.getClassName();

      if (!pastCallback) {
        // Skip everything up to and including our own frame
        if ("datadog.trace.bootstrap.appsec.sca.ScaReachabilityCallback".equals(cls)) {
          pastCallback = true;
        }
        continue;
      }

      if (!pastVulnerableClass) {
        // Skip frames until we leave the vulnerable class (handles library-internal call chains)
        if (cls.equals(vulnerableClass)) {
          pastVulnerableClass = true;
        }
        continue;
      }

      // Skip any remaining frames still inside the vulnerable class
      if (cls.equals(vulnerableClass)) {
        continue;
      }

      // Skip JDK and agent frames — these are not application callsites
      if (cls.startsWith("java.")
          || cls.startsWith("javax.")
          || cls.startsWith("sun.")
          || cls.startsWith("jdk.")
          || cls.startsWith("com.sun.")
          || cls.startsWith("datadog.")) {
        continue;
      }

      return frame;
    }
    return null;
  }

  private ScaReachabilityCallback() {}
}
