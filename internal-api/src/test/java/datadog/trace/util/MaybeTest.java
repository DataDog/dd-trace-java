package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class MaybeTest {

  static final class Widget {
    long count;
    double total;
  }

  @Test
  public void ofPresent() {
    Maybe<String> maybe = Maybe.of("value");
    assertTrue(maybe.isPresent());
    assertEquals("value", maybe.getOrNull());
  }

  @Test
  public void ofAbsent() {
    Maybe<String> maybe = Maybe.of(null);
    assertFalse(maybe.isPresent());
    assertNull(maybe.getOrNull());
  }

  @Test
  public void ofReceiverFunctionPresent() {
    Maybe<Integer> maybe = Maybe.of("value", String::length);
    assertTrue(maybe.isPresent());
    assertEquals(5, maybe.getOrNull());
  }

  @Test
  public void ofReceiverFunctionAbsent() {
    Maybe<Integer> maybe = Maybe.of("value", r -> null);
    assertFalse(maybe.isPresent());
    assertNull(maybe.getOrNull());
  }

  @Test
  public void updateConsumerRunsWhenPresent() {
    Widget w = new Widget();
    Maybe.of(w).update(widget -> widget.count = 42);
    assertEquals(42, w.count);
  }

  @Test
  public void updateConsumerNoOpWhenAbsent() {
    Maybe<Widget> maybe = Maybe.of(null);
    maybe.update(widget -> widget.count = 42);
    assertFalse(maybe.isPresent());
  }

  @Test
  public void updateBiConsumerRunsWhenPresent() {
    Widget w = new Widget();
    Maybe.of(w).update("context", (widget, ctx) -> widget.count = ctx.length());
    assertEquals(7, w.count);
  }

  @Test
  public void updateBiConsumerNoOpWhenAbsent() {
    Maybe<Widget> maybe = Maybe.of(null);
    maybe.update("context", (widget, ctx) -> widget.count = ctx.length());
    assertFalse(maybe.isPresent());
  }

  @Test
  public void updateLongRunsWhenPresent() {
    Widget w = new Widget();
    Maybe.of(w).update(5L, (widget, delta) -> widget.count += delta);
    assertEquals(5, w.count);
  }

  @Test
  public void updateLongNoOpWhenAbsent() {
    Maybe<Widget> maybe = Maybe.of(null);
    maybe.update(5L, (widget, delta) -> widget.count += delta);
    assertFalse(maybe.isPresent());
  }

  @Test
  public void updateDoubleRunsWhenPresent() {
    Widget w = new Widget();
    Maybe.of(w).updateDouble(2.5, (widget, delta) -> widget.total += delta);
    assertEquals(2.5, w.total);
  }

  @Test
  public void updateDoubleNoOpWhenAbsent() {
    Maybe<Widget> maybe = Maybe.of(null);
    maybe.updateDouble(2.5, (widget, delta) -> widget.total += delta);
    assertFalse(maybe.isPresent());
  }

  @Test
  public void ifPresentOrElseRunsActionWhenPresent() {
    StringBuilder sb = new StringBuilder();
    Maybe.of("value").ifPresentOrElse(sb::append, () -> sb.append("empty"));
    assertEquals("value", sb.toString());
  }

  @Test
  public void ifPresentOrElseRunsEmptyActionWhenAbsent() {
    StringBuilder sb = new StringBuilder();
    Maybe.<String>of(null).ifPresentOrElse(sb::append, () -> sb.append("empty"));
    assertEquals("empty", sb.toString());
  }
}
