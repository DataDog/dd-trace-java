package datadog.trace.agent.tooling.muzzle;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.InstrumenterState;
import datadog.trace.agent.tooling.Utils;
import java.util.List;
import net.bytebuddy.matcher.ElementMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MuzzleCheck implements ElementMatcher<ClassLoader> {
  private static final Logger log = LoggerFactory.getLogger(MuzzleCheck.class);

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
      if (log.isDebugEnabled()) {
        final List<Reference.Mismatch> mismatches =
            muzzle.getMismatchedReferenceSources(classLoader);
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
}
