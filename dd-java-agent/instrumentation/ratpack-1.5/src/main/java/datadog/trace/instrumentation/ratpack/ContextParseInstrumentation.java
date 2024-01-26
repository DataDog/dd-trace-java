package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;

@AutoService(Instrumenter.class)
public class ContextParseInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public ContextParseInstrumentation() {
    super("ratpack");
  }

  // so it doesn't apply to ratpack < 1.5
  private static final Reference FILE_IO = new Reference.Builder("ratpack.file.FileIo").build();

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {FILE_IO};
  }

  @Override
  public String instrumentedType() {
    return "ratpack.handling.internal.DefaultContext";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("parse")
            .and(takesArguments(2))
            .and(takesArgument(0, named("ratpack.http.TypedData")))
            .and(takesArgument(1, named("ratpack.parse.Parse"))),
        packageName + ".ContextParseAdvice");
  }
}
