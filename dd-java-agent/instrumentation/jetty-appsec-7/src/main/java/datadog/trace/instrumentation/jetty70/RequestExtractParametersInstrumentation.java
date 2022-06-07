package datadog.trace.instrumentation.jetty70;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.Request;

@AutoService(Instrumenter.class)
public class RequestExtractParametersInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {
  public RequestExtractParametersInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.Request";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("extractParameters").and(takesArguments(0)),
        getClass().getName() + "$ExtractParametersAdvice");
  }

  static final Reference REQUEST_REFERENCE =
      new Reference.Builder("org.eclipse.jetty.server.Request")
          .withField(new String[0], 0, "_baseParameters", "Lorg/eclipse/jetty/util/MultiMap;")
          .build();

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {REQUEST_REFERENCE};
  }

  public static class ExtractParametersAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static int before() {
      return CallDepthThreadLocalMap.incrementCallDepth(Request.class);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    static void after(@Advice.Enter final int depth) {
      if (depth > 0) {
        return;
      }
      CallDepthThreadLocalMap.reset(Request.class);
    }
  }
}
