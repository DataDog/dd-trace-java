package datadog.trace.instrumentation.gson;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class JsonReaderInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType {

  public JsonReaderInstrumentation() {
    super("gson");
  }

  @Override
  public String instrumentedType() {
    return "com.google.gson.stream.JsonReader";
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassNamed("com.google.gson.stream.JsonReader");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor().and(takesArguments(1)).and(takesArgument(0, named("java.io.Reader"))),
        getClass().getName() + "$ConstructAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(returns(String.class))
            .and(namedOneOf("nextName", "nextString"))
            .and(takesNoArguments()),
        getClass().getName() + "$MethodAdvice");
  }

  public static class ConstructAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterInit(
        @Advice.This Object self, @Advice.Argument(0) final java.io.Reader input) {
      final PropagationModule iastModule = InstrumentationBridge.PROPAGATION;
      if (iastModule != null && input != null) {
        iastModule.taintObjectIfTainted(self, input);
      }
    }
  }

  public static class MethodAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void afterMethod(@Advice.This Object self, @Advice.Return final String result) {
      final PropagationModule iastModule = InstrumentationBridge.PROPAGATION;
      if (iastModule != null && result != null) {
        iastModule.taintStringIfTainted(result, self);
      }
    }
  }
}
