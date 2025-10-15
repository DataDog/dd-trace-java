package datadog.trace.instrumentation.jetty904;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;

@AutoService(InstrumenterModule.class)
public final class JettyCommitResponseInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  public JettyCommitResponseInstrumentation() {
    super("jetty");
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
              "Lorg/eclipse/jetty/http/HttpGenerator$ResponseInfo;",
              "Ljava/nio/ByteBuffer;",
              "Z",
              "Lorg/eclipse/jetty/util/Callback;")
          .withField(
              new String[0],
              Reference.EXPECTS_NON_STATIC,
              "_committed",
              "Ljava/util/concurrent/atomic/AtomicBoolean;")
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
              "newResponseInfo",
              "Lorg/eclipse/jetty/http/HttpGenerator$ResponseInfo;")
          .build(),
      new Reference.Builder("org.eclipse.jetty.server.HttpOutput")
          .withMethod(new String[0], Reference.EXPECTS_NON_STATIC, "closed", "V")
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
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("sendResponse")
            .and(takesArguments(4))
            .and(takesArgument(0, named("org.eclipse.jetty.http.HttpGenerator$ResponseInfo")))
            .and(takesArgument(1, named("java.nio.ByteBuffer")))
            .and(takesArgument(2, boolean.class))
            .and(takesArgument(3, named("org.eclipse.jetty.util.Callback"))),
        packageName + ".SendResponseCbAdvice");
  }
}
