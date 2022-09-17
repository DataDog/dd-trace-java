package datadog.trace.instrumentation.java.security;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import datadog.trace.api.iast.InstrumentationBridge;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
public class WeakHashInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

  private static final Config config = Config.get();

  public WeakHashInstrumentation() {
    super("weakhash");
  }

  @Override
  public String instrumentedType() {
    return "java.security.MessageDigest";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("getInstance")
            .and(takesArgument(0, String.class))
            .and(isPublic())
            .and(ElementMatchers.isStatic()),
        WeakHashInstrumentation.class.getName() + "$MessageDigestAdvice");
  }

  public static class MessageDigestAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(value = 0, readOnly = true) String algorithm) {
      InstrumentationBridge.onMessageDigestGetInstance(algorithm);
    }
  }
}
