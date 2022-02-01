package datadog.trace.instrumentation.undertow;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.ExecutorInstrumentationUtils;
import datadog.trace.bootstrap.instrumentation.java.concurrent.RunnableWrapper;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.Map;
import java.util.concurrent.Executor;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

@AutoService(Instrumenter.class)
public final class UndertowInstrumentation extends Instrumenter.Tracing {

  public UndertowInstrumentation() {
    super("undertow", "undertow-1.4");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("io.undertow.server.HttpServerExchange");
  }

  private final ElementMatcher<ClassLoader> CLASS_LOADER_MATCHER =
      hasClassesNamed("io.undertow.server.HttpServerExchange");

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return CLASS_LOADER_MATCHER;
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(Runnable.class.getName(), State.class.getName());
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("dispatch"))
            .and(takesArgument(0, named("java.util.concurrent.Executor")))
            .and(takesArgument(1, named("java.lang.Runnable"))),
        getClass().getName() + "$AddListenerAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[]{
        packageName + ".ExchangeEndSpanListener",
        packageName + ".HttpServerExchangeURIDataAdapter",
        packageName + ".UndertowDecorator",
        packageName + ".UndertowExtractAdapter",
        packageName + ".UndertowExtractAdapter$Request",
        packageName + ".UndertowExtractAdapter$Response"
    };
  }

  public static class AddListenerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static State addListenerEnter(
        @Advice.Argument(value = 1, readOnly = false) Runnable task,
        @Advice.Argument(0) final Executor executor) {
      final AgentScope scope = activeScope();
      final Runnable newTask = RunnableWrapper.wrapIfNeeded(task);
      // It is important to check potentially wrapped task if we can instrument task in this
      // executor. Some executors do not support wrapped tasks.
      if (ExecutorInstrumentationUtils.shouldAttachStateToTask(newTask, executor)) {
        task = newTask;
        final ContextStore<Runnable, State> contextStore =
            InstrumentationContext.get(Runnable.class, State.class);
        State state = ExecutorInstrumentationUtils.setupState(contextStore, newTask, scope);
        state.startThreadMigration();
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
