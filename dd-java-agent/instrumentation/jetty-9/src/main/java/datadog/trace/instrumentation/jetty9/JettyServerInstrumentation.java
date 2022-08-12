package datadog.trace.instrumentation.jetty9;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresMethod;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter.ExcludeType.RUNNABLE;
import static datadog.trace.instrumentation.jetty9.JettyDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.ExcludeFilterProvider;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExcludeFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

@AutoService(Instrumenter.class)
public final class JettyServerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType, ExcludeFilterProvider {

  public JettyServerInstrumentation() {
    super("jetty");
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.HttpChannel";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".JettyDecorator",
      packageName + ".RequestURIDataAdapter",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        takesNoArguments()
            .and(
                named("handle")
                    .or(
                        // In 9.0.3 the handle logic was extracted out to "handle"
                        // but we still want to instrument run in case handle is missing
                        // (without the risk of double instrumenting).
                        named("run")
                            .and(
                                new ElementMatcher.Junction.ForNonNullValues<MethodDescription>() {
                                  @Override
                                  protected boolean doMatch(MethodDescription target) {
                                    // TODO this could probably be made into a nicer matcher.
                                    return !declaresMethod(named("handle"))
                                        .matches(target.getDeclaringType().asErasure());
                                  }
                                }))),
        JettyServerInstrumentation.class.getName() + "$HandleAdvice");
    transformation.applyAdvice(
        // name changed to recycle in 9.3.0
        namedOneOf("reset", "recycle").and(takesNoArguments()),
        JettyServerInstrumentation.class.getName() + "$ResetAdvice");
  }

  @Override
  public Map<ExcludeFilter.ExcludeType, ? extends Collection<String>> excludedClasses() {
    return Collections.singletonMap(
        RUNNABLE,
        Arrays.asList(
            "org.eclipse.jetty.util.thread.strategy.ProduceConsume",
            "org.eclipse.jetty.util.thread.strategy.ExecuteProduceConsume",
            "org.eclipse.jetty.io.ManagedSelector",
            "org.eclipse.jetty.util.thread.TimerScheduler",
            "org.eclipse.jetty.util.thread.TimerScheduler$SimpleTask"));
  }

  public static class HandleAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This final HttpChannel<?> channel) {
      Request req = channel.getRequest();

      Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
      if (existingSpan instanceof AgentSpan) {
        // Request already gone through initial processing, so just activate the span.
        ((AgentSpan) existingSpan).finishThreadMigration();
        return activateSpan((AgentSpan) existingSpan);
      }

      final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(req);
      final AgentSpan span = DECORATE.startSpan(req, extractedContext);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, req, req, extractedContext);

      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);
      req.setAttribute(DD_SPAN_ATTRIBUTE, span);
      req.setAttribute(CorrelationIdentifier.getTraceIdKey(), GlobalTracer.get().getTraceId());
      req.setAttribute(CorrelationIdentifier.getSpanIdKey(), GlobalTracer.get().getSpanId());
      // request may be processed on any thread; signal thread migration
      span.startThreadMigration();
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void closeScope(@Advice.Enter final AgentScope scope) {
      scope.close();
    }
  }

  /**
   * Jetty ensures that connections are reset immediately after the response is sent. This provides
   * a reliable point to finish the server span at the last possible moment.
   */
  public static class ResetAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void stopSpan(@Advice.This final HttpChannel<?> channel) {
      Request req = channel.getRequest();
      Object spanObj = req.getAttribute(DD_SPAN_ATTRIBUTE);
      if (spanObj instanceof AgentSpan) {
        final AgentSpan span = (AgentSpan) spanObj;
        DECORATE.onResponse(span, channel);
        DECORATE.beforeFinish(span);
        // span could have been originated on a different thread and migrated
        span.finishThreadMigration();
        span.finish();
      }
    }

    private void muzzleCheck(HttpChannel<?> connection) {
      connection.run();
    }
  }
}
