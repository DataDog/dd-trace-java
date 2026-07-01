package datadog.trace.agent.tooling.bytebuddy;

import datadog.trace.agent.tooling.bytebuddy.outline.TypePoolFacade;
import java.security.ProtectionDomain;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.scaffold.inline.MethodNameTransformer;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

/**
 * Custom type strategy that captures the target bytecode when matching starts and switches from
 * outline types to full type parsing when the actual transformation begins.
 */
public final class DDOutlineTypeStrategy
    implements AgentBuilder.ClassFileBufferStrategy, AgentBuilder.TypeStrategy {
  public static final DDOutlineTypeStrategy INSTANCE = new DDOutlineTypeStrategy();

  @Override
  public ClassFileLocator resolve(
      String name,
      byte[] binaryRepresentation,
      ClassLoader classLoader,
      JavaModule module,
      ProtectionDomain protectionDomain) {
    TypePoolFacade.beginTransform(name, binaryRepresentation);
    return ClassFileLocator.Simple.of(name, binaryRepresentation);
  }

  @Override
  public TypePool typePool(
      AgentBuilder.PoolStrategy poolStrategy,
      ClassFileLocator classFileLocator,
      ClassLoader classLoader,
      String name) {
    TypePoolFacade.switchContext(classLoader);
    return TypePoolFacade.INSTANCE;
  }

  @Override
  public DynamicType.Builder<?> builder(
      TypeDescription typeDescription,
      ByteBuddy byteBuddy,
      ClassFileLocator classFileLocator,
      MethodNameTransformer methodNameTransformer,
      ClassLoader classLoader,
      JavaModule module,
      ProtectionDomain protectionDomain) {
    TypePoolFacade.enableFullDescriptions();
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
