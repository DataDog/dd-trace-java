package datadog.trace.instrumentation.jersey;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.SourceTypes;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class ParamValueFactoryWithSourceInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public ParamValueFactoryWithSourceInstrumentation() {
    super("jersey");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("apply").and(isPublic()).and(takesArguments(1)),
        ParamValueFactoryWithSourceInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".ThreadLocalSourceType"};
  }

  @Override
  public String instrumentedType() {
    return "org.glassfish.jersey.server.spi.internal.ParamValueFactoryWithSource";
  }

  public static class InstrumenterAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Propagation
    public static void onExit(@Advice.FieldValue("parameterSource") Object parameterSource) {
      switch (parameterSource.toString()) {
        case "COOKIE":
          ThreadLocalSourceType.set(SourceTypes.REQUEST_COOKIE_VALUE);
          break;
        case "PATH":
          ThreadLocalSourceType.set(SourceTypes.REQUEST_PATH_PARAMETER);
          break;
        case "QUERY":
          ThreadLocalSourceType.set(SourceTypes.REQUEST_PARAMETER_VALUE);
          break;
        case "HEADER":
          ThreadLocalSourceType.set(SourceTypes.REQUEST_HEADER_VALUE);
          break;
        case "FORM":
          ThreadLocalSourceType.set(SourceTypes.REQUEST_PARAMETER_VALUE);
          break;
        case "CONTEXT":
          ThreadLocalSourceType.set(SourceTypes.NONE);
          break;
        default:
          ThreadLocalSourceType.set(SourceTypes.NONE);
      }
    }
  }
}
