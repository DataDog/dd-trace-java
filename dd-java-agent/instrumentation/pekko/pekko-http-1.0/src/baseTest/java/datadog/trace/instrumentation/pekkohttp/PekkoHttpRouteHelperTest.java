package datadog.trace.instrumentation.pekkohttp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class PekkoHttpRouteHelperTest {
  @Test
  @SuppressWarnings("unchecked")
  void throwingNestedMatcherRestoresAndRemovesSynchronousFrames() throws Exception {
    Field field = PekkoHttpRouteHelper.class.getDeclaredField("CURRENT");
    field.setAccessible(true);
    ThreadLocal<PekkoHttpRouteHelper.Frame> current =
        (ThreadLocal<PekkoHttpRouteHelper.Frame>) field.get(null);
    PekkoHttpRouteState state = new PekkoHttpRouteState("/items/value");
    state.routeMatched = true;
    PekkoHttpRouteHelper.Frame outer = new PekkoHttpRouteHelper.Frame(null, null, state, null);
    current.set(outer);
    try {
      PekkoHttpRouteHelper.Frame inner = PekkoHttpRouteHelper.enter(null);
      assertSame(outer, inner.parent);
      assertSame(inner, current.get());
      RuntimeException failure = new RuntimeException("matcher failure");
      PekkoHttpRouteHelper.exit(inner, null, failure);
      assertSame(outer, current.get());
      PekkoHttpRouteHelper.exit(outer, null, failure);
      assertNull(current.get());
      assertFalse(state.routeMatched);
      assertNull(PekkoHttpRouteHelper.enter(null));
    } finally {
      current.remove();
    }
  }
}
