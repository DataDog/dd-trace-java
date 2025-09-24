package datadog.trace.instrumentation.jetty10;

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
  public String muzzleDirective() {
    return "after_10";
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
      new Reference.Builder("org.eclipse.jetty.http.HttpFields$Mutable")
          .withMethod(
              new String[0],
              Reference.EXPECTS_NON_STATIC,
              "put",
              "Lorg/eclipse/jetty/http/HttpFields$Mutable;",
              "Ljava/lang/String;",
              "Ljava/lang/String;")
          .build()
    };
  }

  @Override
  public String instrumentedType() {
    return "org.eclipse.jetty.server.HttpChannel";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExtractAdapter",
      packageName + ".ExtractAdapter$Request",
      packageName + ".ExtractAdapter$Response",
      packageName + ".JettyDecorator",
      "datadog.trace.instrumentation.jetty9.RequestURIDataAdapter",
      packageName + ".JettyCommitResponseHelper",
      packageName + ".JettyOnCommitBlockingHelper",
      packageName + ".JettyOnCommitBlockingHelper$CloseCallback",
      "datadog.trace.instrumentation.jetty.JettyBlockResponseFunction",
      "datadog.trace.instrumentation.jetty.JettyBlockingHelper",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("sendResponse")
            .and(takesArguments(4))
            .and(takesArgument(0, named("org.eclipse.jetty.http.MetaData$Response")))
            .and(takesArgument(1, named("java.nio.ByteBuffer")))
            .and(takesArgument(2, boolean.class))
            .and(takesArgument(3, named("org.eclipse.jetty.util.Callback"))),
        packageName + ".SendResponseCbAdvice");
  }
}
