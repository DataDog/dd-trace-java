package datadog.trace.instrumentation.java.lang.classloading;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedNoneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isProtected;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.Constants;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/*
 * Some class loaders do not delegate to their parent, so classes in those class loaders
 * will not be able to see classes in the bootstrap class loader.
 *
 * In particular, instrumentation on classes in those class loaders will not be able to see
 * the shaded OpenTelemetry API classes in the bootstrap class loader.
 *
 * This instrumentation forces all class loaders to delegate to the bootstrap class loader
 * for the classes that we have put in the bootstrap class loader.
 */
@AutoService(InstrumenterModule.class)
public final class ClassloadingInstrumentation extends InstrumenterModule
    implements Instrumenter.ForBootstrap,
        Instrumenter.ForTypeHierarchy,
        Instrumenter.HasMethodAdvice {
  public ClassloadingInstrumentation() {
    super("classloading");
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return true;
  }

  @Override
  protected boolean defaultEnabled() {
    return true;
  }

  @Override
  public String hierarchyMarkerType() {
    return null; // bootstrap type
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    // just an optimization to exclude common class loaders that are known to delegate to the
    // bootstrap loader (or happen to _be_ the bootstrap loader)
    return namedNoneOf("java.lang.ClassLoader", "com.ibm.oti.vm.BootstrapClassLoader")
        .and(extendsClass(named("java.lang.ClassLoader")));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("loadClass"))
            .and(
                takesArguments(1)
                    .and(takesArgument(0, named("java.lang.String")))
                    .or(
                        takesArguments(2)
                            .and(takesArgument(0, named("java.lang.String")))
                            .and(takesArgument(1, named("boolean")))))
            .and(isPublic().or(isProtected()))
            .and(not(isStatic())),
        ClassloadingInstrumentation.class.getName() + "$LoadClassAdvice");
  }

  public static class LoadClassAdvice {
    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class, suppress = Throwable.class)
    public static Class<?> onEnter(@Advice.Argument(0) final String name) {
      if (!name.startsWith("datadog.")) {
        return null; // ignore packages that won't be bundled on the dd-java-agent bootstrap
      }

      // we must access agent types used in the call-depth block like 'Constants' before entering it
      // - otherwise we risk loading these agent types with a non-zero call-depth, which will fail
      final String[] bootstrapPrefixes = Constants.BOOTSTRAP_PACKAGE_PREFIXES;

      // need to use call depth here to prevent re-entry from call to Class.forName() below
      // because on some JVMs (e.g. IBM's, though IBM bootstrap loader is explicitly excluded above)
      // Class.forName() ends up calling loadClass() on the bootstrap loader which would then come
      // back to this instrumentation over and over, causing a StackOverflowError
      final int callDepth = CallDepthThreadLocalMap.incrementCallDepth(ClassLoader.class);
      if (callDepth > 0) {
        return null;
      }
      try {
        for (final String prefix : bootstrapPrefixes) {
          if (name.startsWith(prefix)) {
            return Class.forName(name, false, null);
          }
        }
      } catch (final ClassNotFoundException e) {
        // bootstrap class not found, fall-back to original behaviour
      } finally {
        // need to reset it right away, not waiting until onExit()
        // otherwise it will prevent this instrumentation from being applied when loadClass()
        // ends up calling a ClassFileTransformer which ends up calling loadClass() further down the
        // stack on one of our bootstrap packages (since the call depth check would then suppress
        // the nested loadClass instrumentation)
        CallDepthThreadLocalMap.reset(ClassLoader.class);
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
        @Advice.Return(readOnly = false) Class<?> result,
        @Advice.Enter final Class<?> resultFromBootstrapLoader) {
      if (resultFromBootstrapLoader != null) {
        result = resultFromBootstrapLoader;
      }
    }
  }
}
