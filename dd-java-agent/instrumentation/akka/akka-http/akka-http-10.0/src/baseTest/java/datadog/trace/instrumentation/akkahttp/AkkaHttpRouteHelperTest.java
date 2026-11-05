package datadog.trace.instrumentation.akkahttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import akka.http.scaladsl.model.Uri;
import akka.http.scaladsl.server.PathMatcher;
import datadog.context.Context;
import datadog.context.ContextScope;
import org.junit.jupiter.api.Test;

class AkkaHttpRouteHelperTest {
  private static Uri.Path path(String value) {
    return Uri.apply(value).path();
  }

  @Test
  void failedCompositeRollsBackItsSuccessfulLeaves() {
    AkkaHttpRouteState state = new AkkaHttpRouteState(path("/items/value"));
    state.matched(path("/items/value"), path("items/value"), "/");
    state.matched(path("items/value"), path("/value"), "items");
    state.matched(path("/value"), path("value"), "/");
    state.unmatched(path("/items/value"));
    assertNull(state.template());
    assertEquals(path("/items/value"), state.remaining);
  }

  @Test
  void rejectedAlternativeCannotChangeItsParent() {
    AkkaHttpRouteState parent = new AkkaHttpRouteState(path("/items/value"));
    parent.matched(path("/items/value"), path("/value"), "/items");
    AkkaHttpRouteState child = parent.fork();
    child.matched(path("/value"), path(""), "/{param}");
    assertEquals("/items", parent.template());
    assertEquals("/items/{param}", child.template());
    AkkaHttpRouteState fallback = parent.fork();
    parent.select(fallback);
    assertEquals("/items", parent.template());
  }

  @Test
  void completedAlternativePublishesItsOwnRoute() {
    AkkaHttpRouteState parent = new AkkaHttpRouteState(path("/items/value"));
    AkkaHttpRouteState child = parent.fork();
    child.matched(path("/items/value"), path(""), "/items/{param}");
    parent.select(child);
    assertEquals("/items/{param}", parent.template());
  }

  @Test
  void consumingMatchReplacesLookaheadWithoutChangingItsParent() {
    AkkaHttpRouteState parent = new AkkaHttpRouteState(path("/api/items/value"));
    parent.matched(path("/api/items/value"), path("/items/value"), "/api");
    parent.matched(path("/items/value"), path("/value"), "/{param}");
    AkkaHttpRouteState child = parent.fork();
    child.matched(path("/items/value"), path("items/value"), "/");
    child.matched(path("items/value"), path("/value"), "items");
    child.matched(path("/value"), path(""), "/{param}");
    assertEquals("/api/items/{param}", child.template());
    assertEquals("/api/{param}", parent.template());
  }

  @Test
  void parentCompositeDoesNotDuplicateLeafContributions() {
    AkkaHttpRouteState state = new AkkaHttpRouteState(path("/items"));
    state.matched(path("/items"), path("items"), "/");
    state.matched(path("items"), path(""), "items");
    PathMatcher.Matched<?> result = new PathMatcher.Matched<>(path(""), null, null);
    // No matcher metadata should be accessed for a remainder already recorded by the leaves.
    try (ContextScope ignored = state.storeIn(Context.root()).attach()) {
      AkkaHttpRouteHelper.matched(null, path("/items"), result, null);
    }
    assertEquals("/items", state.template());
  }
}
