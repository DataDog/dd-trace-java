package stackstate.trace.instrumentation.classloaders;

import static net.bytebuddy.matcher.ElementMatchers.failSafe;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static stackstate.trace.agent.tooling.ClassLoaderMatcher.classLoaderHasClasses;

import com.google.auto.service.AutoService;
import io.opentracing.util.GlobalTracer;
import java.lang.reflect.Field;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import stackstate.opentracing.STSTracer;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.agent.tooling.STSAdvice;
import stackstate.trace.agent.tooling.STSTransformers;
import stackstate.trace.bootstrap.CallDepthThreadLocalMap;

@AutoService(Instrumenter.class)
public final class ClassLoaderInstrumentation extends Instrumenter.Configurable {

  public ClassLoaderInstrumentation() {
    super("classloader");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public AgentBuilder apply(final AgentBuilder agentBuilder) {
    return agentBuilder
        .type(
            failSafe(isSubTypeOf(ClassLoader.class)),
            classLoaderHasClasses("io.opentracing.util.GlobalTracer"))
        .transform(STSTransformers.defaultTransformers())
        .transform(STSAdvice.create().advice(isConstructor(), ClassloaderAdvice.class.getName()))
        .asDecorator();
  }

  public static class ClassloaderAdvice {

    @Advice.OnMethodEnter
    public static int constructorEnter() {
      // We use this to make sure we only apply the exit instrumentation
      // after the constructors are done calling their super constructors.
      return CallDepthThreadLocalMap.get(ClassLoader.class).incrementCallDepth();
    }

    // Not sure why, but adding suppress causes a verify error.
    @Advice.OnMethodExit // (suppress = Throwable.class)
    public static void constructorExit(
        @Advice.This final ClassLoader cl, @Advice.Enter final int depth) {
      if (depth == 0) {
        CallDepthThreadLocalMap.get(ClassLoader.class).reset();

        try {
          final Field field = GlobalTracer.class.getDeclaredField("tracer");
          field.setAccessible(true);

          final Object o = field.get(null);
          if (o instanceof STSTracer) {
            final STSTracer tracer = (STSTracer) o;
            tracer.registerClassLoader(cl);
          }
        } catch (final Throwable e) {
        }
      }
    }
  }
}
