package datadog.trace.civisibility.coverage.probe;

import java.io.IOException;
import java.io.Writer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Counts the classes and methods that the transformer leaves without line probes, whose lines are
 * then missing from per-test coverage. Each one is logged at debug level, the counts are logged
 * once per process at warn level, and {@link PatcherStats} writes them with the names of the first
 * ones.
 */
final class ProbeLosses {

  private static final Logger log = LoggerFactory.getLogger(ProbeLosses.class);

  /** How many losses are kept by name; the counts include all of them. */
  static final int MAX_NAMED = 1000;

  private static final AtomicInteger CLASSES = new AtomicInteger();
  private static final AtomicInteger METHODS = new AtomicInteger();
  private static final AtomicInteger METHODS_WITHOUT_EXCEPTION_PROBES = new AtomicInteger();

  private static final Queue<String> NAMED = new ConcurrentLinkedQueue<>();
  private static final AtomicInteger NAMED_COUNT = new AtomicInteger();

  private static final AtomicBoolean WARNED = new AtomicBoolean();

  private ProbeLosses() {}

  /** A class that runs without probes. */
  static void classLost(String className, String reason, @Nullable Throwable cause) {
    CLASSES.incrementAndGet();
    String loss = "class " + className + ": " + reason;
    name(loss);
    log.debug("No line probes in {}", loss, cause);
  }

  /** A method of an instrumented class that runs without probes. */
  static void methodLost(String className, String method, String reason) {
    METHODS.incrementAndGet();
    String loss = "method " + className + '.' + method + ": " + reason;
    name(loss);
    log.debug("No line probes in {}", loss);
  }

  /**
   * A method that keeps its probes but not those of its exception handlers, so lines that an
   * exception cuts short may be missing.
   */
  static void exceptionProbesLost(String className, String method, String reason) {
    METHODS_WITHOUT_EXCEPTION_PROBES.incrementAndGet();
    String loss = "exception probes of " + className + '.' + method + ": " + reason;
    name(loss);
    log.debug("No line probes in {}", loss);
  }

  private static void name(String loss) {
    if (NAMED_COUNT.incrementAndGet() <= MAX_NAMED) {
      NAMED.add(loss);
    }
  }

  /** Logs the counts at warn level, the first time it is called after a loss. */
  static void warnOnce() {
    int classes = CLASSES.get();
    int methods = METHODS.get();
    int exceptionProbes = METHODS_WITHOUT_EXCEPTION_PROBES.get();
    if (classes + methods + exceptionProbes == 0 || !WARNED.compareAndSet(false, true)) {
      return;
    }
    log.warn(
        "Per-test line coverage is missing the lines of {} classes and {} methods that could not"
            + " get line probes, and may miss lines cut short by exceptions in {} more methods. The"
            + " first one is {}. Each one is logged at debug level. This is logged once.",
        classes,
        methods,
        exceptionProbes,
        NAMED.peek());
  }

  /** Writes the counts and the names kept, in the format of the {@link PatcherStats} file. */
  static void writeTo(Writer writer) throws IOException {
    writer.write(CLASSES.get() + "\tlost: classes without probes\n");
    writer.write(METHODS.get() + "\tlost: methods without probes\n");
    writer.write(
        METHODS_WITHOUT_EXCEPTION_PROBES.get() + "\tlost: methods without exception probes\n");
    for (String loss : NAMED) {
      writer.write("1\tlost " + loss.replace('\n', ' ') + '\n');
    }
    int unnamed = NAMED_COUNT.get() - MAX_NAMED;
    if (unnamed > 0) {
      writer.write(unnamed + "\tlost: not named, beyond the first " + MAX_NAMED + "\n");
    }
  }

  static int classCount() {
    return CLASSES.get();
  }

  static int methodCount() {
    return METHODS.get();
  }

  static int exceptionProbeMethodCount() {
    return METHODS_WITHOUT_EXCEPTION_PROBES.get();
  }
}
