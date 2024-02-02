package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.InputStream;
import net.bytebuddy.asm.Advice;

// keep in sync with jersey2 (javax packages)
@AutoService(Instrumenter.class)
public class ReaderInterceptorExecutorInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public ReaderInterceptorExecutorInstrumentation() {
    super("jersey");
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.message.internal.ReaderInterceptorExecutor";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getInputStream").and(takesArguments(0)),
        getClass().getName() + "$InstrumenterAdvice");
  }

  public static class InstrumenterAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    static void after(@Advice.Return final InputStream inputStream) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taint(inputStream, SourceTypes.REQUEST_BODY);
      }
    }
  }
}
