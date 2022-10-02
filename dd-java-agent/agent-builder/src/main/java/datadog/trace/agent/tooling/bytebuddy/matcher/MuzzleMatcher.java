package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterState;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

public final class MuzzleMatcher implements AgentBuilder.RawMatcher {
  private final Instrumenter.Default instrumenter;
  private final int instrumentationId;

  public MuzzleMatcher(Instrumenter.Default instrumenter) {
    this.instrumenter = instrumenter;
    this.instrumentationId = instrumenter.instrumentationId();
  }

  @Override
  public boolean matches(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain) {
    Boolean applicable = InstrumenterState.isApplicable(classLoader, instrumentationId);
    if (null != applicable) {
      return applicable;
    }
    boolean muzzleMatches = instrumenter.muzzleMatches(classLoader, classBeingRedefined);
    if (muzzleMatches) {
      InstrumenterState.applyInstrumentation(classLoader, instrumentationId);
    } else {
      InstrumenterState.blockInstrumentation(classLoader, instrumentationId);
    }
    return muzzleMatches;
  }
}
