package datadog.trace.api.appsec.route;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.appsec.route.VertxRouteParser.Grammar;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class VertxRouteParserCacheTest {

  @Test
  void samePathReturnsCachedInstance() {
    RouteTemplate first = VertxRouteParser.cached("/users/:id", Grammar.DEFAULT);
    assertSame(first, VertxRouteParser.cached(new String("/users/:id"), Grammar.DEFAULT));
    assertEquals("/users/{id}", first.staticRoute());
  }

  @Test
  void differentPathsGiveDifferentTemplates() {
    RouteTemplate users = VertxRouteParser.cached("/users/:id", Grammar.DEFAULT);
    RouteTemplate orders = VertxRouteParser.cached("/orders/:id", Grammar.DEFAULT);
    assertNotSame(users, orders);
    assertEquals("/orders/{id}", orders.staticRoute());
  }

  @Test
  void grammarsUseSeparateCaches() {
    RouteTemplate legacy = VertxRouteParser.cached("/t/:1abc", Grammar.LEGACY);
    RouteTemplate current = VertxRouteParser.cached("/t/:1abc", Grammar.DEFAULT);
    RouteTemplate extended = VertxRouteParser.cached("/t/:1abc", Grammar.EXTENDED);
    assertEquals("/t/%3A1abc", legacy.staticRoute());
    assertEquals("/t/{1abc}", current.staticRoute());
    assertEquals("/t/%3A1abc", extended.staticRoute());
    assertNotSame(legacy, extended);
    assertSame(legacy, VertxRouteParser.cached("/t/:1abc", Grammar.LEGACY));
    assertSame(current, VertxRouteParser.cached("/t/:1abc", Grammar.DEFAULT));
    assertSame(extended, VertxRouteParser.cached("/t/:1abc", Grammar.EXTENDED));
    assertNotSame(Grammar.LEGACY.cache(), Grammar.DEFAULT.cache());
    assertNotSame(Grammar.DEFAULT.cache(), Grammar.EXTENDED.cache());
    assertNotSame(Grammar.LEGACY.cache(), Grammar.EXTENDED.cache());
    assertSame(Grammar.LEGACY.cache(), Grammar.LEGACY.cache());
  }

  @Test
  void nullOrEmptyIsNotExpressible() {
    for (Grammar grammar : Grammar.values()) {
      assertSame(RouteTemplate.NOT_EXPRESSIBLE, VertxRouteParser.cached(null, grammar));
      assertSame(RouteTemplate.NOT_EXPRESSIBLE, VertxRouteParser.cached("", grammar));
    }
  }

  @Test
  void nullOrEmptyNeverReachesTheCache() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    VertxRouteParser.cached(cache, null, Grammar.DEFAULT);
    VertxRouteParser.cached(cache, "", Grammar.DEFAULT);
    assertEquals(0, size(cache));
  }

  @Test
  void unparseablePathIsCachedAsNotExpressible() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    assertSame(
        RouteTemplate.NOT_EXPRESSIBLE, VertxRouteParser.cached(cache, "/x/:id?", Grammar.DEFAULT));
    assertEquals(1, size(cache));
    assertSame(
        RouteTemplate.NOT_EXPRESSIBLE, VertxRouteParser.cached(cache, "/x/:id?", Grammar.DEFAULT));
    assertEquals(1, size(cache));
  }

  @Test
  void parserRuntimeExceptionIsCachedAsNotExpressible() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    AtomicInteger calls = new AtomicInteger();
    Function<String, RouteTemplate> producer =
        VertxRouteParser.safely(path -> {
          calls.incrementAndGet();
          throw new IllegalStateException("parser bug");
        });

    assertSame(RouteTemplate.NOT_EXPRESSIBLE, VertxRouteParser.cached(cache, "/a", producer));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, VertxRouteParser.cached(cache, "/a", producer));
    assertEquals(1, calls.get());
    assertEquals(1, size(cache));
  }

  @Test
  void parserErrorIsNotCached() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    Function<String, RouteTemplate> failing =
        VertxRouteParser.safely(path -> {
          throw new StackOverflowError("transient");
        });

    assertSame(RouteTemplate.NOT_EXPRESSIBLE, VertxRouteParser.cached(cache, "/a/:id", failing));
    assertEquals(0, size(cache));

    RouteTemplate retried = VertxRouteParser.cached(cache, "/a/:id", Grammar.DEFAULT);
    assertEquals("/a/{id}", retried.staticRoute());
    assertEquals(1, size(cache));
  }

  @Test
  void cacheStaysBounded() {
    int capacity = 8;
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(capacity);
    for (int i = 0; i < 1000; i++) {
      String path = "/p" + i + "/:id";
      RouteTemplate template = VertxRouteParser.cached(cache, path, Grammar.DEFAULT);
      assertEquals("/p" + i + "/{id}", template.staticRoute());
      assertTrue(size(cache) <= capacity);
    }
    RouteTemplate last = VertxRouteParser.cached(cache, "/p999/:id", Grammar.DEFAULT);
    assertSame(last, VertxRouteParser.cached(cache, "/p999/:id", Grammar.DEFAULT));
  }

  private static int size(DDCache<String, RouteTemplate> cache) {
    AtomicInteger count = new AtomicInteger();
    cache.visit((k, v) -> count.incrementAndGet());
    return count.get();
  }
}
