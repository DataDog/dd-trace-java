package datadog.trace.agent.tooling.muzzle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.hasSuperClass;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.not;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.io.File;
import java.io.IOException;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

/**
 * Byte-Buddy gradle plugin which creates muzzle-references at compile time.
 *
 * @see "buildSrc/src/main/groovy/InstrumentPlugin.groovy"
 */
public class MuzzleGradlePlugin extends Plugin.ForElementMatcher {
  static {
    SharedTypePools.registerIfAbsent(SharedTypePools.simpleCache());
    HierarchyMatchers.registerIfAbsent(HierarchyMatchers.simpleChecks());
  }

  private final File targetDir;

  @SuppressForbidden // allow use of ElementMatchers.hasSuperClass during build
  public MuzzleGradlePlugin(File targetDir) {
    super(not(isAbstract()).and(hasSuperClass(named(Instrumenter.Default.class.getName()))));
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
