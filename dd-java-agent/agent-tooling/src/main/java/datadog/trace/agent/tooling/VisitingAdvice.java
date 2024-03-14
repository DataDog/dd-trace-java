package datadog.trace.agent.tooling;

import java.security.ProtectionDomain;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

/** {@link AsmVisitorWrapper} type advice. */
final class VisitingAdvice implements Instrumenter.TransformingAdvice {
  private final AsmVisitorWrapper visitor;

  VisitingAdvice(AsmVisitorWrapper visitor) {
    this.visitor = visitor;
  }

  @Override
  public DynamicType.Builder<?> transform(
      DynamicType.Builder<?> builder,
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      ProtectionDomain pd) {
    return builder.visit(visitor);
  }
}
