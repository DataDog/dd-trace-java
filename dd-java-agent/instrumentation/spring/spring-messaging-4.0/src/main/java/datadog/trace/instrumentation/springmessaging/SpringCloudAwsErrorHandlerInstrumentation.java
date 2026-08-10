package datadog.trace.instrumentation.springmessaging;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import io.awspring.cloud.sqs.listener.ListenerExecutionFailedException;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class SpringCloudAwsErrorHandlerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SpringCloudAwsErrorHandlerInstrumentation() {
    super("spring-messaging", "spring-messaging-4");
  }

  @Override
  public String instrumentedType() {
    return "io.awspring.cloud.sqs.listener.pipeline.ErrorHandlerExecutionStage";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".SpringMessageErrorHandlerHelper",
      packageName + ".SpringCloudAwsErrorHandlerHelper"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(ListenerExecutionFailedException.class.getName(), State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("handleError"))
            .and(takesArguments(2))
            .and(takesArgument(1, Throwable.class)),
        SpringCloudAwsErrorHandlerInstrumentation.class.getName()
            + "$ActivateErrorHandlerContinuation");
    transformer.applyAdvice(
        isMethod()
            .and(named("handleErrors"))
            .and(takesArguments(2))
            .and(takesArgument(1, Throwable.class)),
        SpringCloudAwsErrorHandlerInstrumentation.class.getName()
            + "$ActivateErrorHandlerContinuation");
    transformer.applyAdvice(
        isMethod().and(named("process")).and(takesArguments(2)),
        SpringCloudAwsErrorHandlerInstrumentation.class.getName() + "$CleanupContinuation");
    transformer.applyAdvice(
        isMethod().and(named("processMany")).and(takesArguments(2)),
        SpringCloudAwsErrorHandlerInstrumentation.class.getName() + "$CleanupContinuation");
  }

  public static class ActivateErrorHandlerContinuation {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(@Advice.Argument(1) Throwable error) {
      ListenerExecutionFailedException listenerException =
          SpringCloudAwsErrorHandlerHelper.findListenerExecutionFailedException(error);
      if (listenerException == null) {
        return null;
      }
      ContextStore<ListenerExecutionFailedException, State> contextStore =
          InstrumentationContext.get(ListenerExecutionFailedException.class, State.class);
      State state = contextStore.get(listenerException);
      return SpringMessageErrorHandlerHelper.activateContinuation(state);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  public static class CleanupContinuation {

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(
        @Advice.Return(readOnly = false) java.util.concurrent.CompletableFuture<?> result) {
      if (result != null) {
        ContextStore<ListenerExecutionFailedException, State> contextStore =
            InstrumentationContext.get(ListenerExecutionFailedException.class, State.class);
        result =
            result.whenComplete(new SpringCloudAwsErrorHandlerHelper.CleanupOnError(contextStore));
      }
    }
  }
}
