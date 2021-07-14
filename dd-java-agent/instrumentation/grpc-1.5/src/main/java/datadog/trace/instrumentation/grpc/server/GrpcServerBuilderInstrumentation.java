package datadog.trace.instrumentation.grpc.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.CallDepthThreadLocalMap.incrementCallDepth;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import io.grpc.ServerBuilder;
import java.util.Map;
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
  public Map<String, String> contextStore() {
    return singletonMap("io.grpc.ServerBuilder", Boolean.class.getName());
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
    public static boolean onEnter(@Advice.This ServerBuilder<?> serverBuilder) {
      ContextStore<ServerBuilder, Boolean> interceptedStore =
          InstrumentationContext.get(ServerBuilder.class, Boolean.class);
      if (interceptedStore.get(serverBuilder) == null) {
        int callDepth = incrementCallDepth(ServerBuilder.class);
        if (callDepth == 0) {
          interceptedStore.put(serverBuilder, Boolean.TRUE);
          serverBuilder.intercept(TracingServerInterceptor.INSTANCE);
        }
        return true;
      }
      return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter boolean decrement) {
      if (decrement) {
        CallDepthThreadLocalMap.decrementCallDepth(ServerBuilder.class);
      }
    }
  }
}
