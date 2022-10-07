package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.agent.tooling.Instrumenter;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.utility.JavaModule;

public class TargetSystemAwareMatcher implements AgentBuilder.RawMatcher {

  public static final int IAST_ENABLED_FLAG = 5;

  private final Instrumenter instrumenter;

  private final AgentBuilder.RawMatcher delegate;

  public TargetSystemAwareMatcher(
      final Instrumenter instrumenter, final AgentBuilder.RawMatcher delegate) {
    this.instrumenter = instrumenter;
    this.delegate = delegate;
  }

  @Override
  public boolean matches(
      final TypeDescription typeDescription,
      final ClassLoader classLoader,
      final JavaModule module,
      final Class<?> classBeingRedefined,
      final ProtectionDomain protectionDomain) {

    // try to get value from cache otherwise use apply
    Integer allowCode = GlobalIgnoresCache.getAllowCode(typeDescription.getName());
    if (allowCode == IAST_ENABLED_FLAG
        && instrumenter.getTargetSystem() != Instrumenter.TargetSystem.IAST) {
      return false;
    }
    return delegate.matches(
        typeDescription, classLoader, module, classBeingRedefined, protectionDomain);
  }
}
