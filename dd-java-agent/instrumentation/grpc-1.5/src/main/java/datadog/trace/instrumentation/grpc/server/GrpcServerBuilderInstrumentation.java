package datadog.trace.instrumentation.grpc.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import io.grpc.ServerBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class GrpcServerBuilderInstrumentation extends Instrumenter.Tracing {

  public GrpcServerBuilderInstrumentation() {
    super(true, "grpc", "grpc-server");
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named("io.grpc.ServerBuilder"));
  }

  @Override
  public ElementMatcher<? super TypeDescription> shortCutMatcher() {
    return namedOneOf(
        "io.grpc.internal.AbstractServerImplBuilder",
        "io.grpc.alts.AltsServerBuilder",
        "io.grpc.ForwardingServerBuilder",
        "io.grpc.inprocess.InProcessServerBuilder",
        "io.grpc.netty.NettyServerBuilder",
        "io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder",
        "io.grpc.internal.ServerImplBuilder");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".GrpcServerDecorator",
      packageName + ".GrpcServerDecorator$1",
      packageName + ".GrpcExtractAdapter",
      packageName + ".TracingServerInterceptor",
      packageName + ".TracingServerInterceptor$TracingServerCall",
      packageName + ".TracingServerInterceptor$TracingServerCallListener",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
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
