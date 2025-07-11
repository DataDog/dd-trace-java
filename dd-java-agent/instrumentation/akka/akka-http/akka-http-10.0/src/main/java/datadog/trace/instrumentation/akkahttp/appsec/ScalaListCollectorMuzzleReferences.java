package datadog.trace.instrumentation.akkahttp.appsec;

import datadog.trace.agent.tooling.muzzle.Reference;

public interface ScalaListCollectorMuzzleReferences {
  Reference SCALA_LIST_COLLECTOR =
      new Reference.Builder("scala.collection.mutable.ListBuffer")
          .withMethod(
              new String[0],
              Reference.EXPECTS_NON_STATIC | Reference.EXPECTS_PUBLIC,
              "$plus$eq",
              "Lscala/collection/mutable/ListBuffer;",
              "Ljava/lang/Object;")
          .withMethod(
              new String[0],
              Reference.EXPECTS_NON_STATIC | Reference.EXPECTS_PUBLIC,
              "$plus$plus$eq",
              "Lscala/collection/mutable/ListBuffer;",
              "Lscala/collection/TraversableOnce;")
          .or()
          .withMethod(
              new String[0],
              Reference.EXPECTS_NON_STATIC | Reference.EXPECTS_PUBLIC,
              "addOne",
              "Lscala/collection/mutable/ListBuffer;",
              "Ljava/lang/Object;")
          .withMethod(
              new String[0],
              Reference.EXPECTS_NON_STATIC | Reference.EXPECTS_PUBLIC,
              "addAll",
              "Lscala/collection/mutable/ListBuffer;",
              "Lscala/collection/IterableOnce;")
          .build();

  static Reference[] additionalMuzzleReferences() {
    return new Reference[] {SCALA_LIST_COLLECTOR};
  }
}
