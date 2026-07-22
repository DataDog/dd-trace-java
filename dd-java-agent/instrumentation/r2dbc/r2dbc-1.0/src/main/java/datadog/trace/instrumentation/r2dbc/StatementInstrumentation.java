package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.DECORATE;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.INJECT_COMMENT;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.INJECT_TRACE_CONTEXT;
import static datadog.trace.instrumentation.r2dbc.R2dbcDecorator.R2DBC_QUERY;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.r2dbc.spi.Statement;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.reactivestreams.Publisher;

public class StatementInstrumentation
    implements Instrumenter.ForTypeHierarchy, Instrumenter.HasMethodAdvice {

  @Override
  public String hierarchyMarkerType() {
    return "io.r2dbc.spi.Statement";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named("io.r2dbc.spi.Statement"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(isPublic()).and(named("execute")).and(takesArguments(0)),
        StatementInstrumentation.class.getName() + "$StatementExecuteAdvice");
  }

  public static class StatementExecuteAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.This final Statement statement) {
      R2dbcConnectionInfo info =
          InstrumentationContext.get(Statement.class, R2dbcConnectionInfo.class).get(statement);

      String sql = info != null ? info.getSql() : null;

      AgentSpan span = startSpan("r2dbc", R2DBC_QUERY);
      DECORATE.afterStart(span);

      if (sql != null) {
        DECORATE.onStatement(span, sql);
        String dbOperation = R2dbcDecorator.extractDbOperation(sql);
        if (dbOperation != null) {
          span.setTag(Tags.DB_OPERATION, dbOperation);
        }
      }

      if (info != null) {
        DECORATE.onConnection(span, info);
      }

      if (INJECT_COMMENT && INJECT_TRACE_CONTEXT && info != null) {
        span.setTag(InstrumentationTags.DBM_TRACE_INJECTED, true);
      }

      return activateSpan(span);
    }

    @SuppressWarnings("unchecked")
    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Enter final AgentScope scope,
        @Advice.Return(readOnly = false) Publisher<?> publisher,
        @Advice.Thrown final Throwable throwable) {
      AgentSpan span = scope.span();
      if (throwable != null) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        scope.close();
        span.finish();
      } else {
        publisher = new TracingPublisher<>(publisher, span);
        scope.close();
      }
    }
  }
}
