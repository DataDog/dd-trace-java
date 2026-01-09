package datadog.trace.instrumentation.grpc.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.bootstrap.debugger.DebuggerContext.captureCodeOrigin;
import static datadog.trace.bootstrap.debugger.DebuggerContext.marker;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class MethodHandlersInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {
  private static final ElementMatcher<TypeDescription> METHOD_HANDLERS =
      nameEndsWith("$MethodHandlers");

  @Override
  public String hierarchyMarkerType() {
    return "io.grpc.MethodDescriptor";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return METHOD_HANDLERS;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArguments(2)),
        "datadog.trace.instrumentation.grpc.server.MethodHandlersInstrumentation$BuildAdvice");
  }

  public static class BuildAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Object serviceImpl) {
      try {
        Class<?> serviceClass = serviceImpl.getClass();
        Class<?> superClass = serviceClass.getSuperclass();
        while (superClass != null && !superClass.getSimpleName().endsWith("ImplBase")) {
          superClass = superClass.getSuperclass();
        }
        if (superClass != null) {
          // bindService() would be the only method in this case and it's irrelevant
          if (superClass.getDeclaredMethods().length == 1) {
            for (Class<?> i : superClass.getInterfaces()) {
              if (i.getSimpleName().equals("AsyncService")) {
                superClass = i;
                break;
              }
            }
          }
          for (Method method : superClass.getDeclaredMethods()) {
            try {
              marker();
              captureCodeOrigin(
                  serviceClass.getDeclaredMethod(method.getName(), method.getParameterTypes()),
                  true);
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
