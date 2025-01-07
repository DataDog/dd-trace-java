package datadog.trace.instrumentation.grpc.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.bootstrap.debugger.spanorigin.CodeOriginInfo.entry;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.Platform;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class MethodHandlersInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  private static final ElementMatcher<TypeDescription> METHOD_HANDLERS =
      nameEndsWith("$MethodHandlers");

  public MethodHandlersInstrumentation() {
    super("grpc", "grpc-server", "grpc-server-code-origin");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.grpc.MethodDescriptor";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return METHOD_HANDLERS;
  }

  @Override
  public boolean isEnabled() {
    return super.isEnabled() && !Platform.isGraalVM();
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArguments(2)),
        MethodHandlersInstrumentation.class.getName() + "$BuildAdvice");
  }

  public static class BuildAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Object serviceImpl) {
      try {
        Class<?> serviceClass = serviceImpl.getClass();
        Class<?> superclass = serviceClass.getSuperclass();
        if (superclass != null) {
          for (Method method : superclass.getDeclaredMethods()) {
            try {
              entry(serviceClass.getDeclaredMethod(method.getName(), method.getParameterTypes()));
            } catch (Throwable e) {
              // service method not overridden on the impl.  skipping instrumentation.
            }
          }
        }
      } catch (Throwable e) {
        // this should be logged somehow
      }
    }
  }
}
