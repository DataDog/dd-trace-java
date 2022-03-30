package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.IReferenceMatcher;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.agent.tooling.muzzle.ReferenceMatcher;

@AutoService(Instrumenter.class)
public class PathHandlerInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public PathHandlerInstrumentation() {
    super("ratpack");
  }

  @Override
  public String instrumentedType() {
    return "ratpack.path.internal.PathHandler";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".PathBindingPublishingHandler", packageName + ".TokenPathBinderInspector",
    };
  }

  private static final ReferenceMatcher TOKEN_PATH_BINDER_TOKEN_NAMES =
      new ReferenceMatcher(
          new Reference.Builder("ratpack.path.internal.TokenPathBinder")
              .withField(
                  new String[0],
                  Reference.EXPECTS_NON_STATIC,
                  "tokenNames",
                  "Lcom/google/common/collect/ImmutableList;")
              .build());

  // so it doesn't apply to ratpack < 1.5
  private static final ReferenceMatcher FILE_IO =
      new ReferenceMatcher(new Reference.Builder("ratpack.file.FileIo").build());

  private IReferenceMatcher postProcessReferenceMatcher(final ReferenceMatcher origMatcher) {
    return new IReferenceMatcher.ConjunctionReferenceMatcher(
        new IReferenceMatcher.ConjunctionReferenceMatcher(
            origMatcher, TOKEN_PATH_BINDER_TOKEN_NAMES),
        FILE_IO);
  }

  @Override
  public void adviceTransformations(Instrumenter.AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor()
            .and(takesArguments(2))
            .and(takesArgument(0, named("ratpack.path.PathBinder")))
            .and(takesArgument(1, named("ratpack.handling.Handler"))),
        packageName + ".PathHandlerAdvice");
  }
}
