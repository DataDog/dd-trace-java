package datadog.trace.instrumentation.springwebflux.server.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

/** @see org.springframework.http.codec.json.Jackson2Tokenizer */
@AutoService(Instrumenter.class)
public class Json2TokenizerInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
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
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("apply").or(named("tokenize")))
            .and(takesArgument(0, named("org.springframework.core.io.buffer.DataBuffer")))
            .and(takesArguments(1))
            .and(returns(named("reactor.core.publisher.Flux"))),
        packageName + ".Jackson2TokenizerApplyAdvice");
  }
}
