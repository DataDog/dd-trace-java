package datadog.trace.instrumentation.vertx_5_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class HttpServerRequestInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public HttpServerRequestInstrumentation() {
    super("vertx", "vertx-5.0");
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.core.http.HttpServerRequest";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {VertxVersionMatcher.HTTP_HEADERS_INTERNAL};
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.vertx_5_0.server.WafPublishingBodyHandler",
      "datadog.trace.instrumentation.vertx_5_0.server.WafPublishingBodyHandler$BufferWrapper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isPublic()
            .and(named("bodyHandler"))
            .and(takesArguments(1))
            .and(takesArgument(0, named("io.vertx.core.Handler"))),
        HttpServerRequestInstrumentation.class.getName() + "$BodyHandlerAdvice");
  }

  /** @see HttpServerRequest#bodyHandler(Handler) */
  static class BodyHandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    static void after(@Advice.Argument(value = 0, readOnly = false) Handler<Buffer> h) {
      if (h != null) {
        h = new WafPublishingBodyHandler(h);
      }
    }
  }
}
