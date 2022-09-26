package datadog.trace.instrumentation.java.security;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers;
import datadog.trace.api.iast.InstrumentationBridge;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatchers;

@AutoService(Instrumenter.class)
public class WeakCipherInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForBootstrap, Instrumenter.ForSingleType {

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
      InstrumentationBridge.onCipherGetInstance(algorithm);
    }
  }
}
