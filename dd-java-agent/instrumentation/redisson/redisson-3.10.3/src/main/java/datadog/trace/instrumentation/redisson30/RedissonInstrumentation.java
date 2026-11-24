package datadog.trace.instrumentation.redisson30;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.blackholeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.Collections.singletonList;
import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import net.bytebuddy.asm.Advice;
import org.redisson.api.RTransaction;
import org.redisson.client.RedisConnection;
import org.redisson.client.protocol.CommandData;
import org.redisson.client.protocol.CommandsData;

@AutoService(InstrumenterModule.class)
public final class RedissonInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  private static final String REDIS_CONNECTION = "org.redisson.client.RedisConnection";
  private static final String PING_CONNECTION_HANDLER =
      "org.redisson.client.handler.PingConnectionHandler";

  public RedissonInstrumentation() {
    super("redisson", "redis");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {REDIS_CONNECTION, PING_CONNECTION_HANDLER};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("org.redisson.client.protocol.CommandData")))
            .and(isDeclaredBy(named(REDIS_CONNECTION))),
        RedissonInstrumentation.class.getName() + "$RedissonCommandAdvice");

    transformer.applyAdvice(
        isMethod()
            .and(isPublic())
            .and(named("send"))
            .and(takesArgument(0, named("org.redisson.client.protocol.CommandsData")))
            .and(isDeclaredBy(named(REDIS_CONNECTION))),
        RedissonInstrumentation.class.getName() + "$RedissonCommandsAdvice");

    // Lives in this module, rather than its own, so muzzle limits it to the same versions.
    // Gated on its own switch, read once here rather than in the advice.
    InstrumenterConfig config = InstrumenterConfig.get();
    if (config.isIntegrationEnabled(
        singletonList("redis-pool-validation"), config.isIntegrationsEnabled())) {
      transformer.applyAdvice(
          named("sendPing").and(isDeclaredBy(named(PING_CONNECTION_HANDLER))),
          RedissonInstrumentation.class.getName() + "$MutePingAdvice");
    }
  }

  /**
   * Stops the keepalive {@code PING} from producing spans.
   *
   * <p>Redisson pings every pooled connection on a timer, every 30 seconds by default. Those pings
   * run with no active trace, so each became a standalone root span. The advice runs each ping
   * under a black-hole span, so the command it sends creates no span.
   *
   * <p>The {@code PING} sent once while setting up a connection stays traced, along with the other
   * setup commands.
   */
  public static class MutePingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter() {
      return activateSpan(blackholeSpan());
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }

  public static class RedissonCommandAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(
        @Advice.Argument(0) final CommandData<?, ?> command, @Advice.This RedisConnection thiz) {
      final CompletionStage<?> promise =
          PromiseHelper.getPromise(PromiseHelper.COMMAND_GET_PROMISE_HANDLE, command);
      if (promise == null) {
        return null;
      }
      final AgentSpan span = startSpan("redis-command", RedissonClientDecorator.OPERATION_NAME);
      RedissonClientDecorator.DECORATE.afterStart(span);
      RedissonClientDecorator.DECORATE.onPeerConnection(span, thiz.getRedisClient().getAddr());
      RedissonClientDecorator.DECORATE.onStatement(span, command.getCommand().getName());
      promise.whenComplete(new SpanFinishListener(span.captureWithContext()));
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }

    public static void muzzleCheck(final RTransaction b) {
      // added on 3.10.3
      b.getBuckets();
    }
  }

  public static class RedissonCommandsAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(
        @Advice.Argument(0) final CommandsData command, @Advice.This final RedisConnection thiz) {
      final CompletionStage<?> promise =
          PromiseHelper.getPromise(PromiseHelper.COMMANDS_GET_PROMISE_HANDLE, command);
      if (promise == null) {
        return null;
      }
      final AgentSpan span = startSpan("redis-command", RedissonClientDecorator.OPERATION_NAME);
      RedissonClientDecorator.DECORATE.afterStart(span);
      RedissonClientDecorator.DECORATE.onPeerConnection(span, thiz.getRedisClient().getAddr());

      List<String> commandResourceNames = new ArrayList<>();
      for (CommandData<?, ?> commandData : command.getCommands()) {
        commandResourceNames.add(commandData.getCommand().getName());
      }
      RedissonClientDecorator.DECORATE.onStatement(span, String.join(";", commandResourceNames));
      promise.whenComplete(new SpanFinishListener(span.captureWithContext()));
      return activateSpan(span);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void after(@Advice.Enter final ContextScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
