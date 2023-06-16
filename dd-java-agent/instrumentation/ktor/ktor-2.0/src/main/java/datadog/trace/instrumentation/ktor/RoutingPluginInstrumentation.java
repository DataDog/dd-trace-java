package datadog.trace.instrumentation.ktor;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.ktor.server.application.Application;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class RoutingPluginInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public RoutingPluginInstrumentation() {
    super("ktor.experimental");
  }

  @Override
  public String instrumentedType() {
    return "io.ktor.server.routing.Routing$Plugin";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("install"))
            .and(takesArgument(0, named("io.ktor.server.application.Application")))
            .and(takesArgument(1, named("kotlin.jvm.functions.Function1")))
            .and(returns(named("io.ktor.server.routing.Routing"))),
        RoutingPluginInstrumentation.class.getName() + "$ApplicationConstructorAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".KtorDecorator",
      packageName + ".KtorServerTracing",
      packageName + ".KtorServerTracing$Plugin",
      packageName + ".KtorServerTracing$Plugin$install$1",
      packageName + ".KtorServerTracing$Plugin$install$2",
      packageName + ".KtorServerTracing$Plugin$install$3",
      packageName + ".KtorServerTracing$Configuration",
    };
  }

  public static class ApplicationConstructorAdvice {

    @Advice.OnMethodExit
    public static void onEndInvocation(@Advice.Argument(0) final Application application) {
      KtorServerTracing.instrument(application);
    }
  }
}
