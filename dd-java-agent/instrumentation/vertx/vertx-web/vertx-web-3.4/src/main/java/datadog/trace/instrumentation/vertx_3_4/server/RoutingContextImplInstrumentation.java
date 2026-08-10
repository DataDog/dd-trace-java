package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.PARSABLE_HEADER_VALUE;
import static datadog.trace.instrumentation.vertx_3_4.server.VertxVersionMatcher.VIRTUAL_HOST_HANDLER;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
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
          .withMethod(new String[0], 0, "charSet", "Ljava/lang/String;")
          .build();

  public RoutingContextImplInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {packageName + ".FileUploadHelper"};
  }

  @Override
  public String instrumentedType() {
    return "io.vertx.ext.web.impl.RoutingContextImpl";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {PARSABLE_HEADER_VALUE, VIRTUAL_HOST_HANDLER, FILE_UPLOAD_REF};
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("getBodyAsJson").or(named("getBodyAsJsonArray")).and(takesArguments(0)),
        packageName + ".RoutingContextJsonAdvice");
    transformer.applyAdvice(
        named("setSession").and(takesArgument(0, named("io.vertx.ext.web.Session"))),
        packageName + ".RoutingContextSessionAdvice");
    transformer.applyAdvice(
        named("fileUploads").and(takesArguments(0)),
        packageName + ".RoutingContextFilenamesAdvice");
  }
}
