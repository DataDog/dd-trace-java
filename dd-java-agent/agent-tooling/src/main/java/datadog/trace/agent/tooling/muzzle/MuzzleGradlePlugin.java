package datadog.trace.agent.tooling.muzzle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.not;

import datadog.trace.agent.tooling.Instrumenter;
import java.io.File;
import java.io.IOException;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

/**
 * Bytebuddy gradle plugin which creates muzzle-references at compile time.
 *
 * @see "buildSrc/src/main/groovy/InstrumentPlugin.groovy"
 */
public class MuzzleGradlePlugin extends Plugin.ForElementMatcher {
  private final File targetDir;

  public MuzzleGradlePlugin(File targetDir) {
    super(not(isAbstract()).and(extendsClass(named(Instrumenter.Default.class.getName()))));
    this.targetDir = targetDir;
  }

  @Override
  public DynamicType.Builder<?> apply(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      final ClassFileLocator classFileLocator) {
    return builder.visit(new MuzzleVisitor(targetDir));
  }

  @Override
  public void close() throws IOException {}
}
