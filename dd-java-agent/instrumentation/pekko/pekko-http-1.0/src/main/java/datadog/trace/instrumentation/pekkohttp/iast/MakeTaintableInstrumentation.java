package datadog.trace.instrumentation.pekkohttp.iast;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;

@AutoService(InstrumenterModule.class)
public class MakeTaintableInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForKnownTypes,
    Instrumenter.HasTypeAdvice
{
  public MakeTaintableInstrumentation() {
    super("pekko-http");
  }

  /**
   * @see org.apache.pekko.http.scaladsl.model.HttpHeader
   * @return the matching types
   */
  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
        // scaladsl versions extends
        "org.apache.pekko.http.javadsl.model.HttpHeader",
        // javadsl.m.HttpHeader
    // javadsl version wraps scaladsl version
        "org.apache.pekko.http.scaladsl.model.Uri",
        // javadsl is abstract superclass, but
        "org.apache.pekko.http.scaladsl.model.HttpRequest",
        // scaladsl is
    // concrete types of request entities
        "org.apache.pekko.http.scaladsl.model.HttpEntity$Strict",
        "org.apache.pekko.http.scaladsl.model.HttpEntity$Default",
        "org.apache.pekko.http.scaladsl.model.HttpEntity$Chunked",
        // only impl
        "org.apache.pekko.http.scaladsl.server.RequestContextImpl"
    };
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new TaintableVisitor(knownMatchingTypes()));
  }
}
