package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;

@AutoService(Instrumenter.class)
public class ContextParseInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public ContextParseInstrumentation() {
    super("ratpack");
  }

  // so it doesn't apply to ratpack < 1.5
  private static final ReferenceMatcher FILE_IO =
      new ReferenceMatcher(new Reference.Builder("ratpack.file.FileIo").build());

  private IReferenceMatcher postProcessReferenceMatcher(final ReferenceMatcher origMatcher) {
    return new IReferenceMatcher.ConjunctionReferenceMatcher(origMatcher, FILE_IO);
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
