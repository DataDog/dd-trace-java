package datadog.trace.instrumentation.akkahttp.iast;

import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.bytebuddy.iast.TaintableVisitor;

public class MakeTaintableInstrumentation
    implements Instrumenter.ForKnownTypes, Instrumenter.HasTypeAdvice {
  /**
   * @return the matching types
   * @see akka.http.scaladsl.model.HttpHeader
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
