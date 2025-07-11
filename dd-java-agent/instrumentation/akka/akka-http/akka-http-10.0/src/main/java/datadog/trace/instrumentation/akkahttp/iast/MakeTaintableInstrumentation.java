package datadog.trace.instrumentation.akkahttp.iast;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;

@AutoService(InstrumenterModule.class)
public class MakeTaintableInstrumentation extends InstrumenterModule.Iast
    implements Instrumenter.ForKnownTypes, Instrumenter.HasTypeAdvice {
  public MakeTaintableInstrumentation() {
    super("akka-http");
  }

  /**
   * @see akka.http.scaladsl.model.HttpHeader
   * @return the matching types
   */
  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "akka.http.javadsl.model.HttpHeader", // scaladsl versions extends javadsl.m.HttpHeader
      "akka.http.scaladsl.model.Uri", // javadsl version wraps scaladsl version
      "akka.http.scaladsl.model.HttpRequest", // javadsl is abstract superclass, but scaladsl is
      // concrete types of request entities
      "akka.http.scaladsl.model.HttpEntity$Strict",
      "akka.http.scaladsl.model.HttpEntity$Default",
      "akka.http.scaladsl.model.HttpEntity$Chunked",
      // only impl
      "akka.http.scaladsl.server.RequestContextImpl",
    };
  }

  @Override
  public void typeAdvice(TypeTransformer transformer) {
    transformer.applyAdvice(new TaintableVisitor(knownMatchingTypes()));
  }
}
