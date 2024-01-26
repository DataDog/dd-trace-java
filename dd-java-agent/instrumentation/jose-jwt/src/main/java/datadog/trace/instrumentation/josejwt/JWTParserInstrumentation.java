package datadog.trace.instrumentation.josejwt;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Source;
import datadog.trace.api.iast.SourceTypes;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public class JWTParserInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {

  public JWTParserInstrumentation() {
    super("jwt", "auth0-jwt");
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        named("parsePayload").and(isPublic().and(takesArguments(String.class))),
        JWTParserInstrumentation.class.getName() + "$InstrumenterAdvice");
  }

  @Override
  public String instrumentedType() {
    return "com.auth0.jwt.impl.JWTParser";
  }

  public static class InstrumenterAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    @Source(SourceTypes.REQUEST_HEADER_VALUE)
    public static void onEnter(@Advice.Argument(0) String json) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;

      if (module != null) {
        // TODO: We could represent this source more accurately, perhaps tracking the original
        // source, or using a special name.
        module.taint(json, SourceTypes.REQUEST_HEADER_VALUE);
      }
    }
  }
}
