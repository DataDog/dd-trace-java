package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class HeaderParamValueFactoryInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public HeaderParamValueFactoryInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("get").and(isPublic()).and(takesArguments(1)),
        HeaderParamValueFactoryInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ThreadLocalSourceType"};
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.server.internal.inject.HeaderParamValueFactoryProvider$HeaderParamValueFactory";
  }

  public static class InstrumenterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onExit() {
      ThreadLocalSourceType.set(SourceTypes.REQUEST_HEADER_VALUE);
    }
  }
}
