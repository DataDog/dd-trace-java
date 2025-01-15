package datadog.trace.instrumentation.javax.mail;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;

import com.google.auto.service.AutoService;
import com.oracle.truffle.api.library.Message;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class JavaxMailInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public JavaxMailInstrumentation(String instrumentationName, String... additionalNames) {
    super("javaxmailinstrumentation", "javaxmail");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.java.mail";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("javaxMailInstrumentation"),
        JavaxMailInstrumentation.class.getName() + "$MailInjectionAdvice");
  }

  public static class MailInjectionAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    private static void onSend(@Advice.This final Message message) {
      // TODO
    }
  }
}
