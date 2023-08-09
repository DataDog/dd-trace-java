package datadog.trace.instrumentation.armeria.grpc.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;

import com.google.auto.service.AutoService;
import com.linecorp.armeria.internal.shaded.guava.collect.ImmutableList;
import datadog.trace.agent.tooling.Instrumenter;
import io.grpc.ServerInterceptor;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class GrpcServiceBuilderInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public GrpcServiceBuilderInstrumentation() {
    super("armeria-grpc-server", "armeria-grpc", "armeria", "grpc-server", "grpc");
  }

  @Override
  public String instrumentedType() {
    return "com.linecorp.armeria.server.grpc.GrpcServiceBuilder";
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
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
    transformation.applyAdvice(named("build").and(isMethod()), getClass().getName() + "$Build");
  }

  public static final class Build {
    @Advice.OnMethodEnter
    public static void before(
        @Advice.FieldValue(value = "interceptors", readOnly = false) ImmutableList.Builder<ServerInterceptor> interceptors) {
      // Copied from private method interceptors()
      if (interceptors == null) {
        interceptors = ImmutableList.builder();
      }
      interceptors.add(TracingServerInterceptor.INSTANCE);
    }
  }
}
