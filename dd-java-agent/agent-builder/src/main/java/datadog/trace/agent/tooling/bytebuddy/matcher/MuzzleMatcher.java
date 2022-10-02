package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.IntegrationsCollector;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

public final class MuzzleMatcher implements AgentBuilder.RawMatcher {
  private final Instrumenter.Default instrumenter;

  public MuzzleMatcher(Instrumenter.Default instrumenter) {
    this.instrumenter = instrumenter;
  }

  @Override
  public boolean matches(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain) {
    boolean isMatch = instrumenter.muzzleMatches(classLoader, classBeingRedefined);
    if (isMatch && Config.get().isTelemetryEnabled()) {
      IntegrationsCollector.get().update(instrumenter.names(), true);
    }
    return isMatch;
  }
}
