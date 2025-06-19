package datadog.trace.instrumentation.ratpack;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;

@AutoService(InstrumenterModule.class)
public class JsonRendererInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  // so it doesn't apply to ratpack < 1.5
  private static final Reference FILE_IO = new Reference.Builder("ratpack.file.FileIo").build();

  public JsonRendererInstrumentation() {
    super("ratpack");
  }

  @Override
  public String instrumentedType() {
    return "ratpack.jackson.internal.JsonRenderer";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {FILE_IO};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("render"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("ratpack.handling.Context")))
            .and(takesArgument(1, named("ratpack.jackson.JsonRender"))),
        packageName + ".JsonRendererAdvice");
  }
}
