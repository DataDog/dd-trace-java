package datadog.trace.agent.tooling.muzzle;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isAbstract;
import static net.bytebuddy.matcher.ElementMatchers.not;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.WeakMap;
import java.io.IOException;
import java.util.Collections;
import java.util.WeakHashMap;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;

/** Bytebuddy gradle plugin which creates muzzle-references at compile time. */
public class MuzzleGradlePlugin extends Plugin.ForElementMatcher {
  static {
    // prevent WeakMap from logging warning while plugin is running
    WeakMap.Provider.registerIfAbsent(
        new WeakMap.Implementation() {
          @Override
          public <K, V> WeakMap<K, V> get() {
            return new WeakMap.MapAdapter<>(Collections.synchronizedMap(new WeakHashMap<K, V>()));
          }
        });
  }

  public MuzzleGradlePlugin() {
    super(not(isAbstract()).and(extendsClass(named(Instrumenter.Default.class.getName()))));
  }

  @Override
  public DynamicType.Builder<?> apply(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      final ClassFileLocator classFileLocator) {
    return builder.visit(new MuzzleVisitor());
  }

  @Override
  public void close() throws IOException {}
}
