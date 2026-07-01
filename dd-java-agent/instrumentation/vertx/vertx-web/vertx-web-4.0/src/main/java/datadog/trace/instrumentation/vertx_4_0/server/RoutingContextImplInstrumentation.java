package datadog.trace.instrumentation.vertx_4_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;
import io.vertx.ext.web.impl.RoutingContextImpl;

/**
 * @see RoutingContextImpl#getBodyAsJson(int)
 * @see RoutingContextImpl#getBodyAsJsonArray(int)
 */
@AutoService(InstrumenterModule.class)
public class RoutingContextImplInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Reference FILE_UPLOAD_REF =
      new Reference.Builder("io.vertx.ext.web.FileUpload")
          .withMethod(new String[0], 0, "fileName", "Ljava/lang/String;")
          .build();

  public RoutingContextImplInstrumentation() {
    super("vertx", "vertx-4.0");
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {VertxVersionMatcher.HTTP_1X_SERVER_RESPONSE, FILE_UPLOAD_REF};
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.RoutingContextImpl";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getBodyAsJson")
            .or(named("getBodyAsJsonArray"))
            .and(takesArguments(1))
            .and(takesArgument(0, int.class)),
        packageName + ".RoutingContextJsonAdvice");
    transformer.applyAdvice(
        named("setSession").and(takesArgument(0, named("io.vertx.ext.web.Session"))),
        packageName + ".RoutingContextSessionAdvice");
    transformer.applyAdvice(
        named("fileUploads").and(takesArguments(0)),
        packageName + ".RoutingContextFilenamesAdvice");
  }
}
