package datadog.trace.instrumentation.grpc.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.CallDepthThreadLocalMap.incrementCallDepth;
import static java.util.Arrays.asList;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import io.grpc.ServerBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

public class GrpcServerBuilderInstrumentation
    implements Instrumenter.CanShortcutTypeMatching, Instrumenter.HasMethodAdvice {


  @Override
  public boolean onlyMatchKnownTypes() {
    return InstrumenterConfig.get().isIntegrationShortcutMatchingEnabled(asList("grpc", "grpc-server"), true);
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "io.grpc.internal.AbstractServerImplBuilder",
      "io.grpc.alts.AltsServerBuilder",
      "io.grpc.ForwardingServerBuilder",
      "io.grpc.inprocess.InProcessServerBuilder",
      "io.grpc.netty.NettyServerBuilder",
      "io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder",
      "io.grpc.internal.ServerImplBuilder"
    };
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.grpc.ServerBuilder";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("build")).and(takesArguments(0)),
        GrpcServerBuilderInstrumentation.class.getName() + "$BuildAdvice");
  }

  public static class BuildAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.This ServerBuilder<?> serverBuilder) {
      int callDepth = incrementCallDepth(ServerBuilder.class);
      if (callDepth == 0) {
        ContextStore<ServerBuilder, Boolean> interceptedStore =
            InstrumentationContext.get(ServerBuilder.class, Boolean.class);
        if (interceptedStore.get(serverBuilder) == null) {
          interceptedStore.put(serverBuilder, Boolean.TRUE);
          serverBuilder.intercept(TracingServerInterceptor.INSTANCE);
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit() {
      CallDepthThreadLocalMap.decrementCallDepth(ServerBuilder.class);
    }
  }
}
