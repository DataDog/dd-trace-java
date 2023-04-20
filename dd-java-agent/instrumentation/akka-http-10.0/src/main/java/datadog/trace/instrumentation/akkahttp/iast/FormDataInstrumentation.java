package datadog.trace.instrumentation.akkahttp.iast;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import akka.http.scaladsl.model.FormData;
import akka.http.scaladsl.model.Uri;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;

/**
 * Propagate taint from {@link FormData} to {@link Uri.Query}. <code>FormData</code> gets tainted
 * through the unmarshaller tainting mechanism. See {@link MarshallingDirectivesInstrumentation} and
 * {@link UnmarshallerInstrumentation}.
 */
@AutoService(Instrumenter.class)
public class FormDataInstrumentation extends Instrumenter.Iast
    implements Instrumenter.ForSingleType {
  public FormDataInstrumentation() {
    super("akka-http");
  }

  @Override
  public String instrumentedType() {
    return "akka.http.scaladsl.model.FormData";
  }

  /**
   * @param transformation
   * @see UriInstrumentation.TaintQueryAdvice
   */
  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(not(isStatic()))
            .and(named("fields"))
            .and(takesArguments(0))
            .and(returns(named("akka.http.scaladsl.model.Uri$Query"))),
        "datadog.trace.instrumentation.akkahttp.iast.UriInstrumentation$TaintQueryAdvice");
  }
}
