package datadog.trace.instrumentation.graalvmjs;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.graalvmjs.GraalvmjsDecorator.DECORATOR;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.graalvm.polyglot.Source;

@AutoService(InstrumenterModule.class)
public class GraalvmJSInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  public GraalvmJSInstrumentation() {
    super("graalvm-js");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.graalvm.polyglot.Context";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return named(hierarchyMarkerType());
  }

  @Override
  public void methodAdvice(MethodTransformer transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("eval"))
            .and(takesArgument(0, named("org.graalvm.polyglot.Source"))),
        GraalvmJSInstrumentation.class.getName() + "$GraalvmJSEvalAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("parse"))
            .and(takesArgument(0, named("org.graalvm.polyglot.Source"))),
        GraalvmJSInstrumentation.class.getName() + "$GraalvmJSParseAdvice");
  }
  @Override
  public String[] helperClassNames() {
    return new String[] {
        packageName+".GraalvmjsDecorator"
    };
  }
  public static class GraalvmJSEvalAdvice{
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginRequest(@Advice.Argument(0) final Source source) {
      AgentSpan span = DECORATOR.createSpan("eval",source);
      AgentScope agentScope = activateSpan(span);
      return agentScope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATOR.onError(scope.span(), throwable);
      DECORATOR.beforeFinish(scope.span());

      scope.close();
      scope.span().finish();
    }
  }
  public static class GraalvmJSParseAdvice{
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginRequest(@Advice.Argument(0) final Source source) {
      AgentSpan span = DECORATOR.createSpan("parse",source);
      AgentScope agentScope = activateSpan(span);
      return agentScope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
      if (scope == null) {
        return;
      }
      DECORATOR.onError(scope.span(), throwable);
      DECORATOR.beforeFinish(scope.span());

      scope.close();
      scope.span().finish();
    }
  }
}
