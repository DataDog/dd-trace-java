package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.agent.tooling.bytebuddy.outline.OutlinePoolStrategy;
import java.security.ProtectionDomain;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.inline.MethodNameTransformer;
import net.bytebuddy.utility.JavaModule;

/** Strategy that avoids class format changes. */
public final class DDTypeStrategy implements AgentBuilder.TypeStrategy {
  @Override
  public DynamicType.Builder<?> builder(
      TypeDescription typeDescription,
      ByteBuddy byteBuddy,
      ClassFileLocator classFileLocator,
      MethodNameTransformer methodNameTransformer,
      ClassLoader classLoader,
      JavaModule module,
      ProtectionDomain protectionDomain) {
    OutlinePoolStrategy.beginTransform();
    return AgentBuilder.TypeStrategy.Default.REDEFINE_FROZEN.builder(
        typeDescription,
        byteBuddy,
        classFileLocator,
        methodNameTransformer,
        classLoader,
        module,
        protectionDomain);
  }
}
