package datadog.trace.instrumentation.restlet;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.restlet.ResourceDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.restlet.engine.resource.AnnotationInfo;
import org.restlet.resource.ServerResource;

@AutoService(Instrumenter.class)
public final class ResourceInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  private static final String RESTLET_HTTP_OPERATION_NAME = "restlet.request";

  public ResourceInstrumentation() {
    super("restlet-http");
  }

  @Override
  public String instrumentedType() {
    return "org.restlet.resource.ServerResource";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("doHandle"))
            .and(takesArguments(2))
            // In 2.2 this parameter is of type AnnotationInfo. In 2.3+ it is of type
            // MethodAnnotationInfo, which is a subclass of AnnotationInfo
            .and(
                takesArgument(0, extendsClass(named("org.restlet.engine.resource.AnnotationInfo"))))
            .and(takesArgument(1, named("org.restlet.representation.Variant"))),
        getClass().getName() + "$ResourceHandleAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ResourceDecorator",
    };
  }

  public static class ResourceHandleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginRequest(
        @Advice.This final ServerResource serverResource,
        @Advice.Argument(0) final AnnotationInfo annotationInfo) {
      final AgentSpan parent = activeSpan();

      final AgentSpan span = startSpan(RESTLET_HTTP_OPERATION_NAME);
      span.setMeasured(true);
      DECORATE.onRestletSpan(span, parent, serverResource, annotationInfo.getJavaMethod());
      DECORATE.afterStart(span);

      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishRequest(
        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable error) {
      AgentSpan span = scope.span();

      if (null != error) {
        DECORATE.onError(span, error);
      }

      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }
}
