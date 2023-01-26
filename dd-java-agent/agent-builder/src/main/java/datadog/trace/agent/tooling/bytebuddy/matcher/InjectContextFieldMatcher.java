package datadog.trace.agent.tooling.bytebuddy.matcher;

import datadog.trace.agent.tooling.context.FieldBackedContextMatcher;
import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

public final class InjectContextFieldMatcher implements AgentBuilder.RawMatcher {
  private final FieldBackedContextMatcher contextMatcher;
  private final ElementMatcher<ClassLoader> activation;

  public InjectContextFieldMatcher(
      String keyType, String valueType, ElementMatcher<ClassLoader> activation) {
    this.contextMatcher = new FieldBackedContextMatcher(keyType, valueType);
    this.activation = activation;
  }

  @Override
  public boolean matches(
      TypeDescription target,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain pd) {
    return activation.matches(classLoader) && contextMatcher.matches(target, classBeingRedefined);
  }
}
