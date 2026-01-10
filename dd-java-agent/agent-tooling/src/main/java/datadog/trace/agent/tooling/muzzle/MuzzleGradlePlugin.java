package datadog.trace.agent.tooling.muzzle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.concreteClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import java.io.File;
import java.io.IOException;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

/**
 * Byte-Buddy gradle plugin which creates muzzle-references at compile time.
 *
 * @see datadog.gradle.plugin.instrument.BuildTimeInstrumentationPlugin
 */
public class MuzzleGradlePlugin extends Plugin.ForElementMatcher {
  static {
    SharedTypePools.registerIfAbsent(SharedTypePools.simpleCache());
    HierarchyMatchers.registerIfAbsent(HierarchyMatchers.simpleChecks());
  }

  private final File targetDir;

  public MuzzleGradlePlugin(File targetDir) {
    super(concreteClass().and(extendsClass(named(InstrumenterModule.class.getName()))));
    this.targetDir = targetDir;
  }

  @Override
  public DynamicType.Builder<?> apply(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      final ClassFileLocator classFileLocator) {
    return builder.visit(new MuzzleGenerator(targetDir));
  }

  @Override
  public void close() throws IOException {}
}
