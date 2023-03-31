package datadog.trace.instrumentation.jersey;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

@AutoService(Instrumenter.class)
public class JWTParserInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public JWTParserInstrumentation() {
    super("jwt");
  }


  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("parsePayload").and(isPublic().and(takesArguments(String.class))),
        JWTParserInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String instrumentedType() {
    return "com.auth0.jwt.impl.JWTParser";
  }

  public static class InstrumenterAdvice {

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Argument(0) String json) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;

      if (module != null) {
        try {
          module.taint(SourceTypes.REQUEST_HEADER_VALUE, json);
        } catch (final Throwable e) {
          module.onUnexpectedException("JWTParserInstrumentation threw", e);
        }
      }
    }
  }
}
