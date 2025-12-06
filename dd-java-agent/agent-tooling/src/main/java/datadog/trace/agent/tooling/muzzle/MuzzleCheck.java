package datadog.trace.agent.tooling.muzzle;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.InstrumenterState;
import datadog.trace.agent.tooling.Utils;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MuzzleCheck implements ElementMatcher<ClassLoader> {
  private static final Logger log = LoggerFactory.getLogger(MuzzleCheck.class);

  private static final List<String> ERRORS = new CopyOnWriteArrayList<>();
  private static volatile boolean recordErrors = false;

  private final int instrumentationId;
  private final String instrumentationClass;
  private final ReferenceProvider runtimeMuzzleReferences;

  private ReferenceMatcher muzzle;

  public MuzzleCheck(InstrumenterModule module, int instrumentationId) {
    this.instrumentationId = instrumentationId;
    this.instrumentationClass = module.getClass().getName();
    this.runtimeMuzzleReferences = module.runtimeMuzzleReferences();
  }

  public boolean matches(ClassLoader classLoader) {
    Boolean applicable = InstrumenterState.isApplicable(classLoader, instrumentationId);
    if (null != applicable) {
      return applicable;
    }
    boolean muzzleMatches = muzzle().matches(classLoader);
    if (muzzleMatches) {
      InstrumenterState.applyInstrumentation(classLoader, instrumentationId);
    } else {
      InstrumenterState.blockInstrumentation(classLoader, instrumentationId);
      if (recordErrors || log.isDebugEnabled()) {
        final List<Reference.Mismatch> mismatches =
            muzzle.getMismatchedReferenceSources(classLoader);
        if (recordErrors) {
          recordMuzzleMismatch(
              InstrumenterState.describe(instrumentationId), classLoader, mismatches);
        }
        log.debug(
            "Muzzled - {} instrumentation.target.classloader={}",
            InstrumenterState.describe(instrumentationId),
            classLoader);
        for (final Reference.Mismatch mismatch : mismatches) {
          log.debug(
              "Muzzled mismatch - {} instrumentation.target.classloader={} muzzle.mismatch=\"{}\"",
              InstrumenterState.describe(instrumentationId),
              classLoader,
              mismatch);
        }
      }
    }
    return muzzleMatches;
  }

  private ReferenceMatcher muzzle() {
    if (null == muzzle) {
      muzzle =
          InstrumenterModule.loadStaticMuzzleReferences(
                  Utils.getExtendedClassLoader(), instrumentationClass)
              .withReferenceProvider(runtimeMuzzleReferences);
    }
    return muzzle;
  }

  /**
   * Record a muzzle mismatch error for test visibility.
   *
   * @param instrumentationDescription the description of the instrumentation that was blocked
   * @param classLoader the classloader where the instrumentation was blocked
   * @param mismatches the list of mismatch details
   */
  private static void recordMuzzleMismatch(
      String instrumentationDescription,
      ClassLoader classLoader,
      List<Reference.Mismatch> mismatches) {
    StringBuilder sb = new StringBuilder();
    sb.append("Muzzled - ")
        .append(instrumentationDescription)
        .append(" instrumentation.target.classloader=")
        .append(classLoader)
        .append("\n");
    for (Reference.Mismatch mismatch : mismatches) {
      sb.append("  Mismatch: ").append(mismatch).append("\n");
    }
    ERRORS.add(sb.toString());
  }

  // Visible for testing
  public static void enableRecordingAndReset() {
    recordErrors = true;
    ERRORS.clear();
  }

  // Visible for testing
  public static List<String> getErrors() {
    return Collections.unmodifiableList(ERRORS);
  }
}
