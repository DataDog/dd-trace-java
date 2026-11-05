package datadog.trace.instrumentation.akkahttp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class AkkaHttpRouteHelperTest {
  @Test
  @SuppressWarnings("unchecked")
  void throwingNestedMatcherRestoresAndRemovesSynchronousFrames() throws Exception {
    Field field = AkkaHttpRouteHelper.class.getDeclaredField("CURRENT");
    field.setAccessible(true);
    ThreadLocal<AkkaHttpRouteHelper.Frame> current =
        (ThreadLocal<AkkaHttpRouteHelper.Frame>) field.get(null);
    AkkaHttpRouteState state = new AkkaHttpRouteState("/items/value");
    state.routeMatched = true;
    AkkaHttpRouteHelper.Frame outer = new AkkaHttpRouteHelper.Frame(null, null, state, null);
    current.set(outer);
    try {
      AkkaHttpRouteHelper.Frame inner = AkkaHttpRouteHelper.enter(null);
      assertSame(outer, inner.parent);
      assertSame(inner, current.get());
      RuntimeException failure = new RuntimeException("matcher failure");
      AkkaHttpRouteHelper.exit(inner, null, failure);
      assertSame(outer, current.get());
      AkkaHttpRouteHelper.exit(outer, null, failure);
      assertNull(current.get());
      assertFalse(state.routeMatched);
      assertNull(AkkaHttpRouteHelper.enter(null));
    } finally {
      current.remove();
    }
  }
}
