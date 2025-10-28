package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;

/** @see org.springframework.http.codec.json.Jackson2Tokenizer */
@AutoService(InstrumenterModule.class)
public class Json2TokenizerInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public Json2TokenizerInstrumentation() {
    super("spring-webflux");
  }

  @Override
  public String muzzleDirective() {
    return "webflux_with_jackson";
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.http.codec.json.Jackson2Tokenizer";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      "datadog.trace.instrumentation.springwebflux.server.iast.TaintFluxElementsFunction"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("apply").or(named("tokenize")))
            .and(takesArgument(0, named("org.springframework.core.io.buffer.DataBuffer")))
            .and(takesArguments(1))
            .and(returns(named("reactor.core.publisher.Flux"))),
        packageName + ".Jackson2TokenizerApplyAdvice");
  }
}
