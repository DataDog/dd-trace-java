package stackstate.trace.instrumentation.classloaders;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;

import com.google.auto.service.AutoService;
import stackstate.opentracing.STSTracer;
import stackstate.trace.agent.tooling.Instrumenter;
import stackstate.trace.bootstrap.CallDepthThreadLocalMap;
import io.opentracing.util.GlobalTracer;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class ClassLoaderInstrumentation extends Instrumenter.Default {

  public ClassLoaderInstrumentation() {
    super("classloader");
  }

  @Override
  protected boolean defaultEnabled() {
    return false;
  }

  @Override
  public ElementMatcher typeMatcher() {
    return isSubTypeOf(ClassLoader.class);
  }

  @Override
  public Map<ElementMatcher, String> transformers() {
    Map<ElementMatcher, String> transformers = new HashMap<>();
    transformers.put(isConstructor(), ClassloaderAdvice.class.getName());
    return transformers;
  }

  public static class ClassloaderAdvice {

    @Advice.OnMethodEnter
    public static int constructorEnter() {
      // We use this to make sure we only apply the exit instrumentation
      // after the constructors are done calling their super constructors.
      return CallDepthThreadLocalMap.incrementCallDepth(ClassLoader.class);
    }

    // Not sure why, but adding suppress causes a verify error.
    @Advice.OnMethodExit // (suppress = Throwable.class)
    public static void constructorExit(
        @Advice.This final ClassLoader cl, @Advice.Enter final int depth) {
      if (depth == 0) {
        CallDepthThreadLocalMap.reset(ClassLoader.class);

        try {
          final Field field = GlobalTracer.class.getDeclaredField("tracer");
          field.setAccessible(true);

          final Object o = field.get(null);
          // FIXME: This instrumentation will never work. Referencing class DDTracer will throw an exception.
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
