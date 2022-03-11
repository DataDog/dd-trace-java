package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_HTTPSERVEREXCHANGE_DISPATCH;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutorInstrumentationUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import io.undertow.server.HttpServerExchange;
import java.util.Map;
import java.util.concurrent.Executor;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class UndertowInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public UndertowInstrumentation() {
    super("undertow", "undertow-2.0");
  }

  @Override
  public String instrumentedType() {
    return "io.undertow.server.HttpServerExchange";
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("dispatch"))
            .and(takesArgument(0, named("java.util.concurrent.Executor")))
            .and(takesArgument(1, named("java.lang.Runnable"))),
        getClass().getName() + "$AddListenerAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExchangeEndSpanListener",
      packageName + ".HttpServerExchangeURIDataAdapter",
      packageName + ".UndertowDecorator",
      packageName + ".UndertowExtractAdapter",
      packageName + ".UndertowExtractAdapter$Request",
      packageName + ".UndertowExtractAdapter$Response",
      packageName + ".UndertowRunnableWrapper"
    };
  }

  public static class AddListenerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State addListenerEnter(
        @Advice.Argument(value = 1, readOnly = false) Runnable task,
        @Advice.Argument(0) final Executor executor,
        @Advice.This final HttpServerExchange current) {
      final AgentScope scope = activeScope();
      final Runnable newTask = UndertowRunnableWrapper.wrapIfNeeded(task, current, scope.span());
      // It is important to check potentially wrapped task if we can instrument task in this
      // executor. Some executors do not support wrapped tasks.
      if (ExecutorInstrumentationUtils.shouldAttachStateToTask(newTask, executor)) {
        task = newTask;
        final ContextStore<Runnable, State> contextStore =
            InstrumentationContext.get(Runnable.class, State.class);
        State state = ExecutorInstrumentationUtils.setupState(contextStore, newTask, scope);
        state.startThreadMigration();
        // Tell the rest of the instrumentation this exchange has been dispatched so
        // it will not be completed synchronously
        current.putAttachment(DD_HTTPSERVEREXCHANGE_DISPATCH, true);
        return state;
      }
      return null;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addListenerExit(
        @Advice.Argument(0) final Executor executor,
        @Advice.Enter final State state,
        @Advice.Thrown final Throwable throwable) {
      ExecutorInstrumentationUtils.cleanUpOnMethodExit(executor, state, throwable);
    }
  }
}
