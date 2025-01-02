package datadog.trace.instrumentation.armeria.grpc.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class HandlerRegistryBuilderInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public HandlerRegistryBuilderInstrumentation() {
    super("armeria-grpc-server", "armeria-grpc", "armeria", "grpc-server", "grpc");
  }

  @Override
  public String instrumentedType() {
    return "com.linecorp.armeria.server.grpc.HandlerRegistry$Builder";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference(
          new String[0],
          1,
          "com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageDeframer",
          null,
          new String[0],
          new Reference.Field[0],
          new Reference.Method[0])
    };
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("addService"))
            .and(takesArgument(0, named("io.grpc.ServerServiceDefinition"))),
        getClass().getName() + "$AddService");
  }

  public static final class AddService {
    @Advice.OnMethodEnter
    public static void before(
        @Advice.Argument(value = 0, readOnly = false)
            ServerServiceDefinition serverServiceDefinition) {
      serverServiceDefinition =
          ServerInterceptors.intercept(serverServiceDefinition, TracingServerInterceptor.INSTANCE);
    }
  }
}
