package datadog.trace.instrumentation.java.security;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
public class WeakCipherInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public WeakCipherInstrumentation() {
    super("weakcipher");
  }

  @Override
  public String instrumentedType() {
    return "javax.crypto.Cipher";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        NameMatchers.<MethodDescription>named("getInstance")
            .and(takesArgument(0, String.class))
            .and(isPublic())
            .and(ElementMatchers.isStatic()),
        WeakCipherInstrumentation.class.getName() + "$CipherAdvice");
  }

  public static class CipherAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(value = 0, readOnly = true) String algorithm) {
      if (Config.get().getWeakCipherAlgorithms().contains(algorithm.toUpperCase())) {
        final AgentSpan span =
            datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan(
                "WeakCipherAlgorithm_" + algorithm);
        span.finish();
      }
    }
  }
}
