package datadog.trace.instrumentation.ktor;

import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import io.ktor.server.application.Application;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class ApplicationInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {
  public ApplicationInstrumentation() {
    super("ktor.experimental");
  }

  @Override
  public String instrumentedType() {
    return "io.ktor.server.application.Application";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    // TODO find a better way to avoid late init with a volatile check
    // TODO maybe instrument io.ktor.util.pipeline.Pipeline.execute instead?
    transformation.applyAdvice(
        isConstructor(),
        ApplicationInstrumentation.class.getName() + "$ApplicationConstructorAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".KtorDecorator",
      packageName + ".KtorServerTracing",
      packageName + ".KtorServerTracing$Context",
      packageName + ".KtorServerTracing$Plugin",
      packageName + ".KtorServerTracing$Plugin$install$1",
      packageName + ".KtorServerTracing$Plugin$install$2",
      packageName + ".KtorServerTracing$Plugin$lateMonitorSubscribe$1",
      packageName + ".KtorServerTracing$Configuration",
    };
  }

  public static class ApplicationConstructorAdvice {

    @Advice.OnMethodExit
    public static void onEndInvocation(@Advice.This final Application application) {
      KtorServerTracing.instrument(application);
    }
  }
}
