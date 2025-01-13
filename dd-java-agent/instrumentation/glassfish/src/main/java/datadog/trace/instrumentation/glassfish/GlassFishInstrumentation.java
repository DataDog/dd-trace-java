package datadog.trace.instrumentation.glassfish;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.Constants;
import net.bytebuddy.asm.Advice;

/**
 * This instrumenter prevents a mechanism from GlassFish classloader to produces a class not found
 * exception in our tracer. Link to the GH issue:
 * https://github.com/eclipse-ee4j/glassfish/issues/22566 If a class loading is attempted, as an
 * example, as a resource and is it not found, then it is blocked. Successive attempts to load a
 * class as a class (not a resource) will fail because the class is not even tried. We hook into the
 * blocking method to avoid specific namespaces to be blocked.
 */
@AutoService(InstrumenterModule.class)
public final class GlassFishInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public GlassFishInstrumentation() {
    super("glassfish");
  }

  @Override
  public String instrumentedType() {
    return "com.sun.enterprise.v3.server.APIClassLoaderServiceImpl$APIClassLoader";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("addToBlackList")).and(takesArguments(1)),
        GlassFishInstrumentation.class.getName() + "$AvoidGlassFishBlockingAdvice");
  }

  public static class AvoidGlassFishBlockingAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void preventBlockingOfTracerClasses(
        @Advice.Argument(value = 0, readOnly = false) String name) {
      for (final String prefix : Constants.BOOTSTRAP_PACKAGE_PREFIXES) {
        if (name.startsWith(prefix)) {
          name = "__datadog_no_block." + name;
          break;
        }
      }
    }
  }
}
