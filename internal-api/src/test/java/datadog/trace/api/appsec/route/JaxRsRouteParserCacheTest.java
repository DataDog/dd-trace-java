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

class JaxRsRouteParserCacheTest {

  @Test
  void sameRouteReturnsCachedInstance() {
    RouteTemplate first = JaxRsRouteParser.cached("/users/{id: \\d+}");
    assertSame(first, JaxRsRouteParser.cached(new String("/users/{id: \\d+}")));
    assertEquals("/users/{id}", first.staticRoute());
  }

  @Test
  void differentRoutesGiveDifferentTemplates() {
    RouteTemplate users = JaxRsRouteParser.cached("/users/{id}");
    RouteTemplate orders = JaxRsRouteParser.cached("/orders/{id}");
    assertNotSame(users, orders);
    assertEquals("/orders/{id}", orders.staticRoute());
  }

  @Test
  void nullOrEmptyIsNotExpressible() {
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, JaxRsRouteParser.cached(null));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, JaxRsRouteParser.cached(""));
  }

  @Test
  void nullOrEmptyNeverReachesTheCache() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    JaxRsRouteParser.cached(cache, null);
    JaxRsRouteParser.cached(cache, "");
    assertEquals(0, size(cache));
  }

  @Test
  void unparseableRouteIsCachedAsNotExpressible() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, JaxRsRouteParser.cached(cache, "/a/{id"));
    assertEquals(1, size(cache));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, JaxRsRouteParser.cached(cache, "/a/{id"));
    assertEquals(1, size(cache));
  }

  @Test
  void parserRuntimeExceptionIsCachedAsNotExpressible() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    AtomicInteger calls = new AtomicInteger();
    Function<String, RouteTemplate> producer =
        JaxRsRouteParser.safely(route -> {
          calls.incrementAndGet();
          throw new IllegalStateException("parser bug");
        });

    assertSame(RouteTemplate.NOT_EXPRESSIBLE, JaxRsRouteParser.cached(cache, "/a", producer));
    assertSame(RouteTemplate.NOT_EXPRESSIBLE, JaxRsRouteParser.cached(cache, "/a", producer));
    assertEquals(1, calls.get());
    assertEquals(1, size(cache));
  }

  @Test
  void parserErrorIsNotCached() {
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(4);
    Function<String, RouteTemplate> failing =
        JaxRsRouteParser.safely(route -> {
          throw new StackOverflowError("transient");
        });

    assertSame(RouteTemplate.NOT_EXPRESSIBLE, JaxRsRouteParser.cached(cache, "/a/{id}", failing));
    assertEquals(0, size(cache));

    RouteTemplate retried = JaxRsRouteParser.cached(cache, "/a/{id}");
    assertEquals("/a/{id}", retried.staticRoute());
    assertEquals(1, size(cache));
  }

  @Test
  void cacheStaysBounded() {
    int capacity = 8;
    DDCache<String, RouteTemplate> cache = DDCaches.newFixedSizeCache(capacity);
    for (int i = 0; i < 1000; i++) {
      String route = "/p" + i + "/{id}";
      RouteTemplate template = JaxRsRouteParser.cached(cache, route);
      assertEquals(route, template.staticRoute());
      assertTrue(size(cache) <= capacity);
    }
    RouteTemplate last = JaxRsRouteParser.cached(cache, "/p999/{id}");
    assertSame(last, JaxRsRouteParser.cached(cache, "/p999/{id}"));
  }

  private static int size(DDCache<String, RouteTemplate> cache) {
    AtomicInteger count = new AtomicInteger();
    cache.visit((k, v) -> count.incrementAndGet());
    return count.get();
  }
}
