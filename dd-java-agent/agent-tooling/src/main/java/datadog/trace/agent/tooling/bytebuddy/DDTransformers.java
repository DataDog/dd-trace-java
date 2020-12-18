package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.api.Config;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.asm.TypeConstantAdjustment;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.utility.JavaModule;

public class DDTransformers {

  private static final AsmVisitorWrapper CONSTANT_ADJUSTMENT =
      Config.get().isJsr14TargetAdjustmentEnabled()
          ? Jsr14TypeConstantAdjustment.INSTANCE
          : TypeConstantAdjustment.INSTANCE;

  private static final AgentBuilder.Transformer CONSTANT_ADJUSTER =
      new AgentBuilder.Transformer() {
        @Override
        public DynamicType.Builder<?> transform(
            final DynamicType.Builder<?> builder,
            final TypeDescription typeDescription,
            final ClassLoader classLoader,
            final JavaModule javaModule) {
          return builder.visit(CONSTANT_ADJUSTMENT);
        }
      };

  public static AgentBuilder.Transformer defaultTransformers() {
    return CONSTANT_ADJUSTER;
  }
}
