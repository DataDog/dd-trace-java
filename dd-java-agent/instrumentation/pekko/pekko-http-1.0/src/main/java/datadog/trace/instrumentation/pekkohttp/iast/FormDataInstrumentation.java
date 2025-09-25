package datadog.trace.instrumentation.pekkohttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import org.apache.pekko.http.scaladsl.model.FormData;
import org.apache.pekko.http.scaladsl.model.Uri;

/**
 * Propagate taint from {@link FormData} to {@link Uri.Query}. <code>FormData</code> gets tainted
 * through the unmarshaller tainting mechanism. See {@link MarshallingDirectivesInstrumentation} and
 * {@link UnmarshallerInstrumentation}.
 */
@AutoService(InstrumenterModule.class)
public class FormDataInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public FormDataInstrumentation() {
    super("pekko-http");
  }

  @Override
  public String instrumentedType() {
    return "org.apache.pekko.http.scaladsl.model.FormData";
  }

  /**
   * @param transformer
   * @see UriInstrumentation.TaintQueryAdvice
   */
  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("fields"))
            .and(takesArguments(0))
            .and(returns(named("org.apache.pekko.http.scaladsl.model.Uri$Query"))),
        "datadog.trace.instrumentation.pekkohttp.iast.UriInstrumentation$TaintQueryAdvice");
  }
}
