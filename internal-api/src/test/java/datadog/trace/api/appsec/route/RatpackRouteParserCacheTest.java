package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class RatpackRouteParserCacheTest {

  private static final Function<String, RouteTemplate> EXACT =
      RatpackRouteParser.safely(description -> RatpackRouteParser.parse(description, false, false));

  @Test
  void sameDescriptionReturnsCachedInstancePerSlot() {
    for (int s = 0; s < 4; s++) {
      boolean emptyInnerPath = (s & 1) != 0;
      boolean hasRemainder = (s & 2) != 0;
      RouteTemplate first = RatpackRouteParser.cached("users/:id/", emptyInnerPath, hasRemainder);
      assertSame(
          first, RatpackRouteParser.cached(new String("users/:id/"), emptyInnerPath, hasRemainder));
    }
  }

  @Test
  void theFourSlotsAreIndependent() {
    RouteTemplate exact = RatpackRouteParser.cached("users/", false, false);
    RouteTemplate emptyInner = RatpackRouteParser.cached("users/", true, false);
    RouteTemplate remainder = RatpackRouteParser.cached("users/", false, true);
    RouteTemplate emptyInnerRemainder = RatpackRouteParser.cached("users/", true, true);

    assertEquals("/users/", exact.staticRoute());
    assertEquals("/users", emptyInner.staticRoute());
    assertTrue(remainder.needsUnnamedCatchAllRemainder());
    assertTrue(emptyInnerRemainder.needsUnnamedCatchAllRemainder());
    assertNotSame(exact, emptyInner);
    assertNotSame(remainder, emptyInnerRemainder);
    assertNotSame(exact, remainder);
  }

  @Test
  void differentDescriptionsGiveDifferentTemplates() {
    RouteTemplate users = RatpackRouteParser.cached("users/:id", false, false);
    RouteTemplate orders = RatpackRouteParser.cached("orders/:id", false, false);
    assertNotSame(users, orders);
    assertEquals("/orders/{id}", orders.staticRoute());
  }

  @Test
  void nullIsNotExpressibleAndNeverReachesTheCache() {
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, RatpackRouteParser.cached(null, false, false));
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, RatpackRouteParser.cached(cache, null, EXACT));
    assertEquals(0, size(cache));
  }

  @Test
  void emptyDescriptionIsTheRootRouteAndIsCached() {
    assertEquals("/", RatpackRouteParser.cached("", false, false).staticRoute());
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    RouteTemplate root = RatpackRouteParser.cached(cache, "", EXACT);
    assertEquals("/", root.staticRoute());
    assertEquals(1, size(cache));
    assertSame(root, RatpackRouteParser.cached(cache, "", EXACT));
  }

  @Test
  void unexpressibleDescriptionIsCachedAsNotExpressible() {
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, RatpackRouteParser.cached("a/:f?.x", false, false));
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, RatpackRouteParser.cached(cache, "a/:f?.x", EXACT));
    assertEquals(1, size(cache));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, RatpackRouteParser.cached(cache, "a/:f?.x", EXACT));
    assertEquals(1, size(cache));
  }

  @Test
  void parserRuntimeExceptionIsCachedAsNotExpressible() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    AtomicInteger calls = new AtomicInteger();
    Function<String, RouteTemplate> producer =
        RatpackRouteParser.safely(description -> {
          calls.incrementAndGet();
          throw new IllegalStateException("parser bug");
        });

    assertSame(RouteTemplate.NOT_EXPRESSIBLE, RatpackRouteParser.cached(cache, "a", producer));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, RatpackRouteParser.cached(cache, "a", producer));
    assertEquals(1, calls.get());
    assertEquals(1, size(cache));
  }

  @Test
  void parserErrorIsNotCached() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    AtomicInteger calls = new AtomicInteger();
    Function<String, RouteTemplate> failing =
        RatpackRouteParser.safely(description -> {
          calls.incrementAndGet();
          throw new StackOverflowError("transient");
        });

    assertSame(RouteTemplate.NOT_EXPRESSIBLE, RatpackRouteParser.cached(cache, "a/:id", failing));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, RatpackRouteParser.cached(cache, "a/:id", failing));
    assertEquals(2, calls.get());
    assertEquals(0, size(cache));

    RouteTemplate retried = RatpackRouteParser.cached(cache, "a/:id", EXACT);
    assertEquals("/a/{id}", retried.staticRoute());
    assertEquals(1, size(cache));
  }

  @Test
  void cacheStaysBounded() {
    int capacity = 8;
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(capacity);
    for (int i = 0; i < 1000; i++) {
      String description = "p" + i + "/:id";
      RouteTemplate template = RatpackRouteParser.cached(cache, description, EXACT);
      assertEquals("/p" + i + "/{id}", template.staticRoute());
      assertTrue(size(cache) <= capacity);
    }
    RouteTemplate last = RatpackRouteParser.cached(cache, "p999/:id", EXACT);
    assertSame(last, RatpackRouteParser.cached(cache, "p999/:id", EXACT));
  }

  private static int size(DDCache<String, RouteTemplate> cache) {
    AtomicInteger count = new AtomicInteger();
    cache.visit((k, v) -> count.incrementAndGet());
    return count.get();
  }
}
