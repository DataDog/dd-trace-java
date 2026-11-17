package datadog.trace.instrumentation.springweb;

import static datadog.trace.instrumentation.springweb.SpringWebHttpServerDecorator.handlerSpanKeys;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.trace.api.Pair;
import org.junit.jupiter.api.Test;

class HandlerSpanKeysTest {
  @Test
  void reusesKeysForTheSameControllerClass() {
    Pair<String, String> first = handlerSpanKeys(Controller.class);
    Pair<String, String> second = handlerSpanKeys(Controller.class);

    assertSame(first, second);
    assertSame(first.getLeft(), second.getLeft());
    assertSame(first.getRight(), second.getRight());
    assertEquals(
        "dd.handler.span.datadog.trace.instrumentation.springweb.HandlerSpanKeysTest$Controller",
        first.getLeft());
    assertEquals(first.getLeft() + ".continue", first.getRight());
  }

  @Test
  void distinguishesRuntimeControllerClasses() {
    Pair<String, String> parent = handlerSpanKeys(Controller.class);
    Pair<String, String> child = handlerSpanKeys(ChildController.class);

    assertNotSame(parent, child);
    assertEquals(
        "dd.handler.span.datadog.trace.instrumentation.springweb.HandlerSpanKeysTest$ChildController",
        child.getLeft());
  }

  static class Controller {}

  static class ChildController extends Controller {}
}
