package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.MuzzleCheck;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

public final class MuzzleMatcher implements AgentBuilder.RawMatcher {
  private final MuzzleCheck muzzleCheck;

  public MuzzleMatcher(Instrumenter.Default instrumenter) {
    this.muzzleCheck = new MuzzleCheck(instrumenter);
  }

  @Override
  public boolean matches(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain) {
    return muzzleCheck.matches(classLoader);
  }
}
