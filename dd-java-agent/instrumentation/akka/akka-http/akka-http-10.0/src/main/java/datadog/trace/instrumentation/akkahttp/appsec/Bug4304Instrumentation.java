package datadog.trace.instrumentation.akkahttp.appsec;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.declaresField;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import akka.stream.stage.GraphStageLogic;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers;
import datadog.trace.agent.tooling.muzzle.Reference;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Field;
import java.util.regex.Pattern;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

/** See https://github.com/akka/akka-http/issues/4304 */
@AutoService(InstrumenterModule.class)
public class Bug4304Instrumentation extends InstrumenterModule.AppSec
    implements Instrumenter.ForTypeHierarchy,
        Instrumenter.WithTypeStructure,
        Instrumenter.HasMethodAdvice {
  public Bug4304Instrumentation() {
    super("akka-http");
  }

  @Override
  public String hierarchyMarkerType() {
    return "akka.http.impl.engine.server.HttpServerBluePrint";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AkkaBlockResponseFunction",
      packageName + ".BlockingResponseHelper",
      packageName + ".ScalaListCollector",
      "datadog.trace.instrumentation.akkahttp.AkkaHttpServerDecorator",
      "datadog.trace.instrumentation.akkahttp.AkkaHttpServerHeaders",
      "datadog.trace.instrumentation.akkahttp.UriAdapter",
    };
  }

  @Override
  public Reference[] additionalMuzzleReferences() {
    return ScalaListCollectorMuzzleReferences.additionalMuzzleReferences();
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return nameStartsWith("akka.http.impl.engine.server.HttpServerBluePrint$ControllerStage$$anon$")
        .and(HierarchyMatchers.extendsClass(named("akka.stream.stage.GraphStageLogic")))
        .and(MatchesOneHundredContinueStageAnonClass.INSTANCE);
  }

  public static class MatchesOneHundredContinueStageAnonClass
      implements ElementMatcher<TypeDescription> {
    public static final ElementMatcher<TypeDescription> INSTANCE =
        new MatchesOneHundredContinueStageAnonClass();

    private MatchesOneHundredContinueStageAnonClass() {}

    private static final Pattern ANON_CLASS_PATTERN =
        Pattern.compile(
            "akka\\.http\\.impl\\.engine\\.server\\.HttpServerBluePrint\\$ControllerStage\\$\\$anon\\$"
                + "\\d+\\$OneHundredContinueStage\\$\\$anon\\$\\d+");

    @Override
    public boolean matches(TypeDescription td) {
      return ANON_CLASS_PATTERN.matcher(td.getName()).matches();
    }
  }

  @Override
  public ElementMatcher<TypeDescription> structureMatcher() {
    return declaresField(named("oneHundredContinueSent"));
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isConstructor(), Bug4304Instrumentation.class.getName() + "$GraphStageLogicAdvice");
  }

  static class GraphStageLogicAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    @SuppressForbidden
    static void after(@Advice.This GraphStageLogic thiz)
        throws NoSuchFieldException, IllegalAccessException {
      AgentSpan span = activeSpan();
      RequestContext reqCtx;
      if (span == null
          || (reqCtx = span.getRequestContext()) == null
          || reqCtx.getData(RequestContextSlot.APPSEC) == null) {
        return;
      }

      BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
      if (brf instanceof AkkaBlockResponseFunction) {
        AkkaBlockResponseFunction abrf = (AkkaBlockResponseFunction) brf;
        if (abrf.isBlocking() && abrf.isUnmarshallBlock()) {
          Field f = thiz.getClass().getDeclaredField("oneHundredContinueSent");
          f.setAccessible(true);
          f.set(thiz, true);
        }
      }
    }
  }
}
