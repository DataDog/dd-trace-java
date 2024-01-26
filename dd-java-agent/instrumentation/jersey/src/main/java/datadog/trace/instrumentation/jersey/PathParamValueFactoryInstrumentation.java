package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class PathParamValueFactoryInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public PathParamValueFactoryInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("get").and(takesArguments(1)),
        PathParamValueFactoryInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ThreadLocalSourceType"};
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.server.internal.inject.PathParamValueFactoryProvider$PathParamValueFactory";
  }

  public static class InstrumenterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_PATH_PARAMETER)
    public static void onExit() {
      ThreadLocalSourceType.set(SourceTypes.REQUEST_PATH_PARAMETER);
    }
  }
}
