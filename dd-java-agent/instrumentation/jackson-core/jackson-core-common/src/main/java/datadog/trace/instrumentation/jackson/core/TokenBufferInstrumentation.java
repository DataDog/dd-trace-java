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
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.propagation.PropagationModule;
import net.bytebuddy.asm.Advice;

/**
 * @see TokenBuffer#asParser()
 * @see TokenBuffer#asParser(ObjectCodec codec)
 * @see TokenBuffer#asParser(JsonParser codec)
 */
@AutoService(InstrumenterModule.class)
public class TokenBufferInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public TokenBufferInstrumentation() {
    super("jackson-core");
  }

  @Override
  public String instrumentedType() {
    return "com.fasterxml.jackson.databind.util.TokenBuffer";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
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
        module.taintObjectIfTainted(parser, tokenBuffer);
      }
    }
  }
}
