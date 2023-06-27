package datadog.trace.instrumentation.jackson.core;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.returns;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

/**
 * @see TokenBuffer#asParser()
 * @see TokenBuffer#asParser(ObjectCodec codec)
 * @see TokenBuffer#asParser(JsonParser codec)
 */
@AutoService(Instrumenter.class)
public class TokenBufferInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public TokenBufferInstrumentation() {
    super("jackson-core");
  }

  @Override
  public String instrumentedType() {
    return "com.fasterxml.jackson.databind.util.TokenBuffer";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("asParser"))
            .and(isPublic())
            .and(returns(named("com.fasterxml.jackson.core.JsonParser"))),
        TokenBufferInstrumentation.class.getName() + "$AsParserAdvice");
  }

  public static class AsParserAdvice {
    @Advice.OnMethodExit
    @Propagation
    public static void onExit(
        @Advice.This TokenBuffer tokenBuffer, @Advice.Return JsonParser parser) {
      final PropagationModule module = InstrumentationBridge.PROPAGATION;
      if (module != null) {
        module.taintIfInputIsTainted(parser, tokenBuffer);
      }
    }
  }
}
