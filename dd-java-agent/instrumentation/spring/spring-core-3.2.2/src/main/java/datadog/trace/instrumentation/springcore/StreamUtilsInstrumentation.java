package datadog.trace.instrumentation.springcore;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import net.bytebuddy.asm.Advice;
import org.springframework.util.StreamUtils;

@AutoService(InstrumenterModule.class)
public final class StreamUtilsInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public StreamUtilsInstrumentation() {
    super("spring-core");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("copyToString"))
            .and(takesArguments(2))
            .and(takesArgument(0, InputStream.class))
            .and(takesArgument(1, Charset.class)),
        StreamUtilsInstrumentation.class.getName() + "$SpringAdvice");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.util.StreamUtils";
  }

  public static class SpringAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    @Propagation
    public static void checkReturnedObject(
        @Advice.Return String string, @Advice.Argument(0) final InputStream in) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (string != null && module != null) {
        module.taintStringIfTainted(string, in);
      }
    }

    private static void muzzleCheck() throws IOException {
      StreamUtils.copyToString(
          new ByteArrayInputStream("test".getBytes()), Charset.defaultCharset());
    }
  }
}
