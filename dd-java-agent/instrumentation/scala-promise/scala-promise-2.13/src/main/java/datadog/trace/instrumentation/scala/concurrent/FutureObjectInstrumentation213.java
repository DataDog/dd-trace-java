package datadog.trace.instrumentation.scala.concurrent;

import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static scala.concurrent.impl.Promise.Transformation;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import scala.concurrent.Future$;
import scala.util.Try;

/**
 * This instrumentation makes sure that the static {@code Future} named {@code unit} in the Scala
 * object for {@code Future} does not retain the context for the span that created it. If it did,
 * every {@code Future} created via one of the static methods like {@code map} would always pick up
 * that context and propagate it forward, which is quite unexpected and not very relevant.
 */
@AutoService(Instrumenter.class)
public class FutureObjectInstrumentation213 extends Instrumenter.Tracing {

  public FutureObjectInstrumentation213() {
    super("scala_future_object", "scala_concurrent");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("scala.concurrent.Future$");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("scala.util.Try", AgentSpan.class.getName());
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return Collections.singletonMap(isTypeInitializer(), getClass().getName() + "$ClassInit");
  }

  public static final class ClassInit {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static <T> void afterInit() {
      Try<?> result = Future$.MODULE$.unit().value().get();
      InstrumentationContext.get(Try.class, AgentSpan.class).put(result, null);
    }

    /** Promise.Transformation was introduced in scala 2.13 */
    private static void muzzleCheck(final Transformation callback) {
      callback.submitWithValue(null);
    }
  }
}
