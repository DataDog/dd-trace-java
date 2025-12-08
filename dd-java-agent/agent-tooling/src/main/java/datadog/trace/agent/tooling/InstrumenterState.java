package datadog.trace.agent.tooling;

import datadog.instrument.utils.ClassLoaderValue;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLongArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracks instrumentation state, such as where it was applied and where it was blocked.
 *
 * <p>Each {@link InstrumenterModule} is allocated a unique {@code instrumentationId} by {@code
 * AgentInstaller} which it registers with {@link InstrumenterState} and uses to apply or block the
 * instrumentation per-class-loader, for example if {@code MuzzleCheck} detects an incompatibility.
 */
public final class InstrumenterState {
  private static final Logger log = LoggerFactory.getLogger(InstrumenterState.class);

  public interface Observer {
    void applied(Iterable<String> instrumentationNames);
  }

  // constants for representing a bitset as a series of words
  private static final int ADDRESS_BITS_PER_WORD = 6;
  private static final int BITS_PER_WORD = 1 << ADDRESS_BITS_PER_WORD;
  private static final int BIT_INDEX_MASK = BITS_PER_WORD - 1;

  // represent each status as 2 adjacent bits
  private static final int BLOCKED = 0b01;
  private static final int APPLIED = 0b10;

  private static final int STATUS_BITS = 0b11;

  private static Iterable<String>[] instrumentationNames = new Iterable[0];
  private static String[] instrumentationClasses = new String[0];

  private static long[] defaultState = {};

  /** Tracks which instrumentations were applied (per-class-loader) and which were blocked. */
  private static final ClassLoaderValue<AtomicLongArray> classLoaderStates =
      new ClassLoaderValue<AtomicLongArray>() {
        @Override
        protected AtomicLongArray computeValue(ClassLoader cl) {
          return new AtomicLongArray(defaultState);
        }
      };

  private static Observer observer;

  private InstrumenterState() {}

  /** Pre-sizes internal structures to accommodate the highest expected id. */
  public static void initialize(int instrumentationCount) {
    instrumentationNames = Arrays.copyOf(instrumentationNames, instrumentationCount);
    instrumentationClasses = Arrays.copyOf(instrumentationClasses, instrumentationNames.length);
  }

  /** Registers an instrumentation's details. */
  public static void registerInstrumentation(InstrumenterModule module, int instrumentationId) {
    if (instrumentationId >= instrumentationNames.length) {
      // note: the 'initialize' method pre-sizes these arrays to avoid repeated allocations here
      instrumentationNames = Arrays.copyOf(instrumentationNames, instrumentationId + 16);
      instrumentationClasses = Arrays.copyOf(instrumentationClasses, instrumentationNames.length);
    }
    instrumentationNames[instrumentationId] = module.names();
    instrumentationClasses[instrumentationId] = module.getClass().getName();
  }

  /** Registers an observer to be notified whenever an instrumentation is applied. */
  public static void setObserver(Observer observer) {
    InstrumenterState.observer = observer;
  }

  /** Resets the default instrumentation state so nothing is blocked or applied. */
  public static void resetDefaultState() {
    int instrumentationCount = instrumentationNames.length;

    int wordsPerClassLoaderState =
        ((instrumentationCount << 1) + BITS_PER_WORD - 1) >> ADDRESS_BITS_PER_WORD;

    if (defaultState.length > 0) { // optimization: skip clear if there's no old state
      classLoaderStates.clear();
    }

    defaultState = new long[wordsPerClassLoaderState];
  }

  /**
   * Does the instrumentation apply to the given class-loader?
   *
   * @return {@code null} if checks such as muzzle are still pending
   */
  public static Boolean isApplicable(ClassLoader classLoader, int instrumentationId) {
    int status = currentState(classLoader, instrumentationId);
    return status == 0 ? null : status == APPLIED;
  }

  /** Records that the instrumentation was applied to the given class-loader. */
  public static void applyInstrumentation(ClassLoader classLoader, int instrumentationId) {
    updateState(classLoader, instrumentationId, APPLIED);
    if (log.isDebugEnabled()) {
      log.debug(
          "Instrumentation applied - {} instrumentation.target.classloader={}",
          describe(instrumentationId),
          classLoader);
    }
    if (null != observer) {
      observer.applied(instrumentationNames[instrumentationId]);
    }
  }

  /** Records that the instrumentation is blocked for the given class-loader. */
  public static void blockInstrumentation(ClassLoader classLoader, int instrumentationId) {
    updateState(classLoader, instrumentationId, BLOCKED);
    if (log.isDebugEnabled()) {
      log.debug(
          "Instrumentation blocked - {} instrumentation.target.classloader={}",
          describe(instrumentationId),
          classLoader);
    }
  }

  /** Records that the instrumentation is blocked by default. */
  public static void blockInstrumentation(int instrumentationId) {
    int bitIndex = instrumentationId << 1;
    int wordIndex = bitIndex >> ADDRESS_BITS_PER_WORD;
    long bitsToSet = ((long) BLOCKED) << (bitIndex & BIT_INDEX_MASK);
    synchronized (defaultState) {
      defaultState[wordIndex] |= bitsToSet;
    }
  }

  public static String describe(int instrumentationId) {
    if (instrumentationId >= 0 && instrumentationId < instrumentationNames.length) {
      return "instrumentation.names=["
          + String.join(",", instrumentationNames[instrumentationId])
          + "] instrumentation.class="
          + instrumentationClasses[instrumentationId];
    } else {
      return "<unknown instrumentation>";
    }
  }

  private static int currentState(ClassLoader classLoader, int instrumentationId) {
    return currentState(classLoaderState(classLoader), instrumentationId << 1);
  }

  private static void updateState(ClassLoader classLoader, int instrumentationId, int status) {
    updateState(classLoaderState(classLoader), instrumentationId << 1, status);
  }

  private static AtomicLongArray classLoaderState(ClassLoader classLoader) {
    return classLoaderStates.get(classLoader);
  }

  private static int currentState(AtomicLongArray state, int bitIndex) {
    int wordIndex = bitIndex >> ADDRESS_BITS_PER_WORD;
    return STATUS_BITS & (int) (state.get(wordIndex) >> (bitIndex & BIT_INDEX_MASK));
  }

  private static void updateState(AtomicLongArray state, int bitIndex, int status) {
    int wordIndex = bitIndex >> ADDRESS_BITS_PER_WORD;
    long bitsToSet = ((long) status) << (bitIndex & BIT_INDEX_MASK);
    long wordState = state.get(wordIndex);
    while (!state.compareAndSet(wordIndex, wordState, wordState | bitsToSet)) {
      wordState = state.get(wordIndex);
    }
  }

  public static String summary() {
    StringBuilder summary = new StringBuilder();
    classLoaderStates.visit(
        (loader, state) -> {
          summary.append(loader != null ? loader.getClass().getName() : "<bootstrap>");
          summarizeState(summary, state);
          summary.append("\n\n");
        });
    return summary.toString();
  }

  private static void summarizeState(StringBuilder summary, AtomicLongArray state) {
    for (int wordIndex = 0; wordIndex < state.length(); wordIndex++) {
      int instrumentationId = wordIndex * (BITS_PER_WORD >> 1); // 2 bits per status
      long wordState = state.get(wordIndex);
      while (wordState != 0) {
        if ((wordState & STATUS_BITS) != 0) {
          if ((wordState & APPLIED) != 0) {
            summary.append("\n    APPLIED  ");
          } else {
            summary.append("\n    BLOCKED  ");
          }
          summary
              .append(instrumentationClasses[instrumentationId])
              .append("  ")
              .append(instrumentationNames[instrumentationId]);
        }
        instrumentationId++;
        wordState >>>= 2; // move onto next 2 status bits
      }
    }
  }
}
