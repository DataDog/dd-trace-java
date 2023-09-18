package datadog.trace.instrumentation.jetty9421;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.muzzle.Reference;

@AutoService(Instrumenter.class)
public final class JettyCommitResponseInstrumentation extends Instrumenter.AppSec
    implements Instrumenter.ForSingleType {

  public JettyCommitResponseInstrumentation() {
    super("jetty");
  }

  @Override
  public String muzzleDirective() {
    return "between_9421_and_10";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference.Builder("org.eclipse.jetty.server.HttpChannel")
          .withMethod(
              new String[0],
              Reference.EXPECTS_PUBLIC_OR_PROTECTED | Reference.EXPECTS_NON_STATIC,
              "sendResponse",
              "Z",
              "Lorg/eclipse/jetty/http/MetaData$Response;",
              "Ljava/nio/ByteBuffer;",
              "Z",
              "Lorg/eclipse/jetty/util/Callback;")
          .withMethod(
              new String[0],
              Reference.EXPECTS_PUBLIC_OR_PROTECTED | Reference.EXPECTS_NON_STATIC,
              "commit",
              "V",
              "Lorg/eclipse/jetty/http/MetaData$Response;")
          .withField(
              new String[0],
              Reference.EXPECTS_NON_STATIC,
              "_transport",
              "Lorg/eclipse/jetty/server/HttpTransport;")
          .build(),
      new Reference.Builder("org.eclipse.jetty.server.Response")
          .withMethod(
              new String[0],
              Reference.EXPECTS_PUBLIC_OR_PROTECTED | Reference.EXPECTS_NON_STATIC,
              "newResponseMetaData",
              "Lorg/eclipse/jetty/http/MetaData$Response;")
          .build(),
      new Reference.Builder("org.eclipse.jetty.server.HttpOutput")
          .withMethod(new String[0], Reference.EXPECTS_NON_STATIC, "closed", "V")
          .or()
          .withMethod(
              new String[0],
              Reference.EXPECTS_NON_STATIC,
              "completed",
              "V",
              "Ljava/lang/Throwable;")
          .build(),
    };
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.HttpChannel";
  }

  @Override
  public String[] helperClassNames() {
    String pkg9 = "datadog.trace.instrumentation.jetty9";
    return new String[] {
      pkg9 + ".ExtractAdapter",
      pkg9 + ".ExtractAdapter$Request",
      pkg9 + ".ExtractAdapter$Response",
      pkg9 + ".JettyDecorator",
      pkg9 + ".RequestURIDataAdapter",
      "datadog.trace.instrumentation.jetty.JettyBlockResponseFunction",
      "datadog.trace.instrumentation.jetty.JettyBlockingHelper",
      packageName + ".JettyCommitResponseHelper",
      packageName + ".JettyOnCommitBlockingHelper",
      packageName + ".JettyOnCommitBlockingHelper$CloseCallback",
    };
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("sendResponse")
            .and(takesArguments(4))
            .and(takesArgument(0, named("org.eclipse.jetty.http.MetaData$Response")))
            .and(takesArgument(1, named("java.nio.ByteBuffer")))
            .and(takesArgument(2, boolean.class))
            .and(takesArgument(3, named("org.eclipse.jetty.util.Callback"))),
        packageName + ".SendResponseCbAdvice");
  }
}
