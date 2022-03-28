package datadog.trace.instrumentation.ratpack;

import static net.bytebuddy.matcher.ElementMatchers.*;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

@AutoService(Instrumenter.class)
public class ContextParseInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public ContextParseInstrumentation() {
    super("ratpack");
  }

  @Override
  public String instrumentedType() {
    return "ratpack.handling.internal.DefaultContext";
  }

  @Override
  public void adviceTransformations(Instrumenter.AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("parse")
            .and(takesArguments(2))
            .and(takesArgument(0, named("ratpack.http.TypedData")))
            .and(takesArgument(1, named("ratpack.parse.Parse"))),
        packageName + ".ContextParseAdvice");
  }
}
