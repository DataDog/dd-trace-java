package datadog.trace.instrumentation.vertx_5_0.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;

@AutoService(InstrumenterModule.class)
public class RoutingContextImplInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final Reference FILE_UPLOAD_REF =
      new Reference.Builder("io.vertx.ext.web.FileUpload")
          .withMethod(new String[0], 0, "fileName", "Ljava/lang/String;")
          .withMethod(new String[0], 0, "uploadedFileName", "Ljava/lang/String;")
          .withMethod(new String[0], 0, "contentType", "Ljava/lang/String;")
          .build();

  public RoutingContextImplInstrumentation() {
    super("vertx", "vertx-5.0");
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.RoutingContextImpl";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {VertxVersionMatcher.HTTP_HEADERS_INTERNAL, FILE_UPLOAD_REF};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("fileUploads").and(takesArguments(0)),
        packageName + ".RoutingContextFilenamesAdvice");
  }
}
