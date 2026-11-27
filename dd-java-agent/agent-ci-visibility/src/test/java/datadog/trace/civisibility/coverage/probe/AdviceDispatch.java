package datadog.trace.civisibility.coverage.probe;

/**
 * What advice in instrumented JDK methods calls, as a mocking library's dispatcher is: on the
 * watched threads it runs {@link #hook}, which has probes. {@link ProbeInstrumentedJdkForkedTest}
 * defines this class in the bootstrap class loader, where JDK classes can link to it, and reaches
 * it only by reflection. Its code calls no method, so that the advice cannot run inside itself.
 */
public final class AdviceDispatch {

  /** Nesting depth beyond which the hook no longer runs, so that a recursion ends. */
  public static final int DEPTH_LIMIT = 8;

  public static volatile Runnable hook;

  /** The threads on which the hook runs. */
  public static volatile Thread[] watched = new Thread[0];

  /** The nesting depth of advice calls on each watched thread, by its index in {@link #watched}. */
  public static final int[] DEPTHS = new int[8];

  /** The deepest nesting of advice calls on a watched thread. */
  public static volatile int maxDepth;

  /** Times the hook ran. */
  public static volatile int calls;

  private AdviceDispatch() {}

  public static void enter() {
    Thread current = Thread.currentThread();
    Thread[] threads = watched;
    for (int i = 0; i < threads.length; i++) {
      if (threads[i] == current) {
        int depth = ++DEPTHS[i];
        try {
          if (depth > maxDepth) {
            maxDepth = depth;
          }
          Runnable code = hook;
          if (code != null && depth <= DEPTH_LIMIT) {
            calls++;
            code.run();
          }
        } finally {
          DEPTHS[i]--;
        }
        return;
      }
    }
  }
}
