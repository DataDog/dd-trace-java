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
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import io.awspring.cloud.sqs.listener.ListenerExecutionFailedException;
import java.util.Map;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class SpringCloudAwsListenerAdapterInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public SpringCloudAwsListenerAdapterInstrumentation() {
    super("spring-messaging", "spring-messaging-4");
  }

  @Override
  public String instrumentedType() {
    return "io.awspring.cloud.sqs.listener.adapter.AbstractMethodInvokingListenerAdapter";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".SpringMessageErrorHandlerHelper"};
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap(ListenerExecutionFailedException.class.getName(), State.class.getName());
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("invokeHandler")).and(takesArguments(1)),
        SpringCloudAwsListenerAdapterInstrumentation.class.getName() + "$InvokeHandlerAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(named("createListenerException"))
            .and(takesArguments(2))
            .and(takesArgument(1, Throwable.class)),
        SpringCloudAwsListenerAdapterInstrumentation.class.getName()
            + "$CreateListenerExceptionAdvice");
  }

  public static class InvokeHandlerAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter() {
      SpringMessageErrorHandlerHelper.enterAwsListenerInvocation();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit() {
      SpringMessageErrorHandlerHelper.clearPendingContinuation();
      SpringMessageErrorHandlerHelper.exitAwsListenerInvocation();
    }
  }

  public static class CreateListenerExceptionAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(@Advice.Return ListenerExecutionFailedException listenerException) {
      ContextStore<ListenerExecutionFailedException, State> contextStore =
          InstrumentationContext.get(ListenerExecutionFailedException.class, State.class);
      State state = contextStore.putIfAbsent(listenerException, State.FACTORY);
      SpringMessageErrorHandlerHelper.transferPendingContinuation(state);
    }
  }
}
