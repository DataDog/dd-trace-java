package datadog.trace.instrumentation.lettuce5;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameEndsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * The Lettuce reactive client is based on <a href=http://reactives-treams.org">Reactive
 * Streams</a>.<br>
 *
 * <p>The whole command execution process is driven by an associated {@linkplain
 * io.lettuce.core.RedisPublisher#RedisSubscription} instance which creates a {@linkplain
 * reactor.core.publisher.Mono} (for a singleton command) or {@linkplain
 * reactor.core.publisher.Flux} (for a command set) instance. <br>
 * Once the subscription is created the client will subscribe to it and by doing this will trigger
 * the reactive command processing. <br>
 * As a part of the subscription process a special {@linkplain
 * io.lettuce.core.RedisPublisher.SubscriptionCommand} wrapper command instance is created which
 * will be handed over for transmission to Redis in {@linkplain
 * io.lettuce.core.RedisPublisher.RedisSubscription#dispatchCommand()} method. At exactly this point
 * the associated span can be marked for migration as the Redis response (if any) may be processed
 * by a different thread.<br>
 * When the response is received back from Redis server the subscription command is connected with
 * the incoming data and {@linkplain io.lettuce.core.RedisPublisher.SubscriptionCommand#complete()}
 * method is invoked to start processing the response. At this point the command span migration can
 * be finilized as the span processing resumes on this particular thread.
 *
 * <p>When it is a set of commands that is being executed we want to have the information about the
 * number of processed commands - this can be achieved by hooking into {@linkplain
 * io.lettuce.core.RedisPublisher.RedisSubscription#onNext(Object)} method, incrementing the tracked
 * count until the associated subscription command has been cancelled. In order to propagate the
 * parent span active at the time of issuing the Redis command it is necessary to instrument
 * {@linkplain io.lettuce.core.AbstractRedisReactiveCommands}, intercepting all <code>createMono
 * </code> and <code>create*Flux</code> methods - capturing the parent span when {@linkplain
 * reactor.core.publisher.Mono} or {@linkplain reactor.core.publisher.Flux} instance is created and
 * reactivating each time they are subscribed.
 *
 * <p>Each command is transmitted to redis and the transnmission starts at
 */
@AutoService(Instrumenter.class)
public class LettuceReactiveClientInstrumentation extends Instrumenter.Tracing {

  public LettuceReactiveClientInstrumentation() {
    super("lettuce", "lettuce-5", "lettuce-5-rx");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return namedOneOf(
        "io.lettuce.core.RedisPublisher$RedisSubscription",
        "io.lettuce.core.RedisPublisher$SubscriptionCommand",
        "io.lettuce.core.AbstractRedisReactiveCommands");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".rx.RedisSubscriptionSubscribeAdvice$State",
      packageName + ".rx.RedisSubscriptionState",
      packageName + ".rx.LettuceFlowTracker",
      packageName + ".LettuceInstrumentationUtil",
      packageName + ".LettuceClientDecorator"
    };
  }

  @Override
  public Map<String, String> contextStore() {
    Map<String, String> store = new HashMap<>(3);
    store.put("org.reactivestreams.Subscription", packageName + ".rx.RedisSubscriptionState");
    store.put("io.lettuce.core.protocol.RedisCommand", AgentSpan.class.getName());
    return store;
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod().and(named("subscribe")), packageName + ".rx.RedisSubscriptionSubscribeAdvice");
    transformation.applyAdvice(
        isMethod().and(named("onNext")), packageName + ".rx.RedisSubscriptionAdvanceAdvice");
    transformation.applyAdvice(
        isMethod().and(named("dispatchCommand")),
        packageName + ".rx.RedisSubscriptionDispatchCommandAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(
                ElementMatchers.<MethodDescription>isDeclaredBy(
                    named("io.lettuce.core.RedisPublisher$SubscriptionCommand")))
            .and(namedOneOf("complete", "cancel")),
        packageName + ".rx.RedisSubscriptionCommandCompleteAdvice");

    // SubscriptionCommand structure has changed due to
    // https://github.com/lettuce-io/lettuce-core/issues/1576 in 5.3.6
    transformation.applyAdvice(
        isMethod()
            .and(
                ElementMatchers.<MethodDescription>isDeclaredBy(
                    named("io.lettuce.core.RedisPublisher$SubscriptionCommand")))
            .and(namedOneOf("doOnComplete")),
        packageName + ".rx.RedisSubscriptionCommandOnCompleteAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(
                ElementMatchers.<MethodDescription>isDeclaredBy(
                    named("io.lettuce.core.RedisPublisher$SubscriptionCommand")))
            .and(named("onError")),
        packageName + ".rx.RedisSubscriptionCommandErrorAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(named("createMono"))
            .and(takesArgument(0, named("java.util.function.Supplier")))
            .and(returns(named("reactor.core.publisher.Mono"))),
        packageName + ".rx.LettuceMonoCreationAdvice");
    transformation.applyAdvice(
        isMethod()
            .and(nameStartsWith("create"))
            .and(nameEndsWith("Flux"))
            .and(takesArgument(0, named("java.util.function.Supplier")))
            .and(returns(named("reactor.core.publisher.Flux"))),
        packageName + ".rx.LettuceFluxCreationAdvice");
  }
}
