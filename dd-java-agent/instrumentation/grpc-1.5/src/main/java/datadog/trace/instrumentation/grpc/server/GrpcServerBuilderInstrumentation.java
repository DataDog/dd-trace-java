package datadog.trace.instrumentation.grpc.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeHasSuperType;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import io.grpc.ServerBuilder;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class GrpcServerBuilderInstrumentation extends Instrumenter.Tracing {

  public GrpcServerBuilderInstrumentation() {
    super("grpc", "grpc-server");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return safeHasSuperType(named("io.grpc.ServerBuilder"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GrpcServerDecorator",
      packageName + ".GrpcExtractAdapter",
      packageName + ".TracingServerInterceptor",
      packageName + ".TracingServerInterceptor$TracingServerCall",
      packageName + ".TracingServerInterceptor$TracingServerCallListener",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(
        isMethod().and(isPublic()).and(named("build")).and(takesArguments(0)),
        GrpcServerBuilderInstrumentation.class.getName() + "$BuildAdvice");
  }

  public static class BuildAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This ServerBuilder<?> serverBuilder) {
      int callDepth = CallDepthThreadLocalMap.incrementCallDepth(ServerBuilder.class);
      if (callDepth == 0) {
        serverBuilder.intercept(TracingServerInterceptor.INSTANCE);
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit() {
      CallDepthThreadLocalMap.decrementCallDepth(ServerBuilder.class);
    }
  }
}
