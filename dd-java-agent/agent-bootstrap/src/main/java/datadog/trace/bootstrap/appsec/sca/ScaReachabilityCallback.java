package datadog.trace.bootstrap.appsec.sca;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  private static final Logger log = LoggerFactory.getLogger(ScaReachabilityCallback.class);

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

  /** Runtime dedup: "vulnId|artifact|dotClassName|methodName" tuples already reported. */
  private static final Set<String> reported = ConcurrentHashMap.newKeySet();

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
   * (baked in at transform time) and are used for deduplication. The handler (registered by {@code
   * ScaReachabilitySystem}) is responsible for capturing the callsite from the current thread stack
   * and reporting it to telemetry - keeping this class minimal as required for bootstrap.
   */
  public static void onMethodHit(
      String vulnId,
      String artifact,
      String version,
      String dotClassName,
      String methodName,
      int line) {
    try {
      Handler h = handler;
      if (h == null) {
        return;
      }
      // Include version and dotClassName: version isolates hits across artifact versions loaded
      // in separate classloaders; dotClassName distinguishes classes with the same method name.
      String key = vulnId + "|" + artifact + "|" + version + "|" + dotClassName + "|" + methodName;
      if (reported.add(key)) {
        h.onMethodHit(vulnId, artifact, version, dotClassName, methodName, line);
      }
    } catch (Exception t) {
      // Never propagate to application code - SCA detection is observation-only
      log.debug("SCA Reachability: error in onMethodHit for {}#{}", dotClassName, methodName, t);
    }
  }

  private ScaReachabilityCallback() {}
}
