package datadog.trace.instrumentation.r2dbc;

import static datadog.trace.agent.tooling.bytebuddy.matcher.ClassLoaderMatchers.hasClassNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.InstrumentationContext;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import java.util.Collections;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(InstrumenterModule.class)
public class R2dbcInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public R2dbcInstrumentation() {
    super("r2dbc");
  }

  @Override
  public String instrumentedType() {
    return "io.r2dbc.spi.ConnectionFactories";
  }

  @Override
  public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
    // The bundled r2dbc-proxy's callback machinery is written directly against Reactor's
    // Flux/Mono (not just org.reactivestreams.Publisher, which is all r2dbc-spi itself
    // requires), so skip wrapping in the (rare) case an application uses a non-Reactor
    // r2dbc-spi driver, to avoid a NoClassDefFoundError when the proxy is invoked.
    return hasClassNamed("reactor.core.publisher.Flux");
  }

  @Override
  public boolean isHelperClass(String className) {
    // The bundled r2dbc-proxy is relocated (binary shading, see build.gradle) into this
    // package rather than compiled from this module's own source, so it's never picked up by
    // module-output discovery — claim it explicitly instead.
    return className.startsWith(packageName + ".shaded.");
  }

  @Override
  public Map<String, String> contextStore() {
    return Collections.singletonMap(
        "io.r2dbc.spi.Connection", "io.r2dbc.spi.ConnectionFactoryOptions");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("find"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.r2dbc.spi.ConnectionFactoryOptions"))),
        getClass().getName() + "$ConnectionFactoriesAdvice");
  }

  public static class ConnectionFactoriesAdvice {

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Return(readOnly = false) ConnectionFactory factory,
        @Advice.Argument(0) ConnectionFactoryOptions options) {
      if (factory != null) {
        ContextStore<Connection, ConnectionFactoryOptions> connectionOptionsStore =
            InstrumentationContext.get(Connection.class, ConnectionFactoryOptions.class);
        factory =
            R2dbcTracingSupport.wrapConnectionFactory(factory, options, connectionOptionsStore);
      }
    }
  }
}
