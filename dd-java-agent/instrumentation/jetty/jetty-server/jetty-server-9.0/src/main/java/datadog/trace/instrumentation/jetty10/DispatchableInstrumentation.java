package datadog.trace.instrumentation.jetty10;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.muzzle.Reference;

@AutoService(InstrumenterModule.class)
public class DispatchableInstrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {
  public DispatchableInstrumentation() {
    super("jetty");
  }

  @Override
  public String muzzleDirective() {
    return "named_dispatches";
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return new Reference[] {
      new Reference.Builder("org.eclipse.jetty.server.HttpChannel$RequestDispatchable")
          .withField(
              new String[0],
              Reference.EXPECTS_NON_STATIC,
              "this$0",
              "Lorg/eclipse/jetty/server/HttpChannel;")
          .build(),
      new Reference.Builder("org.eclipse.jetty.server.HttpChannel$AsyncDispatchable")
          .withField(
              new String[0],
              Reference.EXPECTS_NON_STATIC,
              "this$0",
              "Lorg/eclipse/jetty/server/HttpChannel;")
          .build(),
    };
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.jetty.JettyBlockingHelper",
    };
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.eclipse.jetty.server.HttpChannel$RequestDispatchable",
      "org.eclipse.jetty.server.HttpChannel$RequestAsyncDispatchable",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod().and(named("dispatch")).and(isPublic()).and(takesArguments(0)),
        packageName + ".DispatchableAdvice");
  }
}
