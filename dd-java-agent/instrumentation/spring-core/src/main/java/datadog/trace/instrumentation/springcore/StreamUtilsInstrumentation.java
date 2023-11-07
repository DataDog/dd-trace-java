package datadog.trace.instrumentation.springcore;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.InputStream;
import java.nio.charset.Charset;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class StreamUtilsInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  private static final String INSTRUMENTED_CLASS = "org.springframework.util.StreamUtils";

  public StreamUtilsInstrumentation() {
    super("spring-core");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassNamed(INSTRUMENTED_CLASS);
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("copyToString"))
            .and(takesArguments(2))
            .and(takesArgument(0, InputStream.class))
            .and(takesArgument(1, Charset.class)),
        StreamUtilsInstrumentation.class.getName() + "$SpringAdvice");
  }

  @Override
  public String instrumentedType() {
    return INSTRUMENTED_CLASS;
  }

  public static class SpringAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void checkReturnedObject(
        @Advice.Return String string, @Advice.Argument(0) final InputStream in) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (in != null && string != null && !string.isEmpty()) {
        module.taintIfTainted(string, in);
      }
    }
  }
}
