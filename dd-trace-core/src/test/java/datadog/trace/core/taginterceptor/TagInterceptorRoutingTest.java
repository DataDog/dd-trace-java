package datadog.trace.core.taginterceptor;

import static datadog.trace.bootstrap.instrumentation.api.ServiceNameSources.SPLIT_BY_TAGS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.api.TagMap;
import datadog.trace.core.DDSpanContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The guard that licenses the INTERCEPTED flag to exist.
 *
 * <p>{@code KnownTagCodec.INTERCEPTED} is a declaration in {@code tag-conventions.java.yaml};
 * {@code TagInterceptor.interceptTag} is the code. An earlier version of the flag was deleted
 * precisely because those two could drift apart with nothing to notice — a flag set on a tag the
 * switch does not handle silently boxes a value for no reason, and a case label with no flag makes
 * the pre-screen skip a tag the interceptor was supposed to route, which is a behaviour change with
 * no error message at all.
 *
 * <p>So the agreement is asserted rather than maintained by convention: for every tag in the
 * registry, the flag must say exactly what the switch does. "What the switch does" is read
 * behaviourally, by asking whether the tag reaches the {@code default} branch — configuring every
 * known tag as a split-service tag makes that branch, and only that branch, call {@code
 * setServiceName(value, SPLIT_BY_TAGS)}.
 */
class TagInterceptorRoutingTest {

  private static final String PROBE = "probe-value";

  /** Every serial the registry has assigned, walked until the resolver stops naming them. */
  private static List<String> allKnownTagNames() {
    List<String> names = new ArrayList<>();
    for (int serial = 1; ; serial++) {
      String name = KnownTagCodec.nameOf(KnownTagCodec.makeTagId(serial));
      if (name == null) {
        return names;
      }
      names.add(name);
    }
  }

  /**
   * An interceptor for which the {@code default} branch is observable: every known tag is a
   * split-service tag, so a tag that falls through calls {@code setServiceName(value,
   * SPLIT_BY_TAGS)} and one that is routed does not.
   */
  private static TagInterceptor interceptorSplittingOn(Set<String> tags) {
    return new TagInterceptor(false, "inferred-service", tags, new RuleFlags(), false);
  }

  private static DDSpanContext probeSpan() {
    DDSpanContext span = mock(DDSpanContext.class);
    // interceptServletContext reads it before deciding; the mock default (null) would NPE
    when(span.getServiceName()).thenReturn("");
    return span;
  }

  private static boolean fellThroughToDefault(DDSpanContext span) {
    return mockingDetails(span).getInvocations().stream()
        .anyMatch(
            i ->
                "setServiceName".equals(i.getMethod().getName())
                    && i.getArguments().length == 2
                    && SPLIT_BY_TAGS.equals(i.getArguments()[1]));
  }

  @Test
  void interceptedFlagMatchesTheDispatchSwitchExactly() {
    List<String> names = allKnownTagNames();
    assertFalse(names.isEmpty(), "registry resolved no tags at all");

    TagInterceptor interceptor = interceptorSplittingOn(new LinkedHashSet<>(names));

    List<String> flaggedButNotHandled = new ArrayList<>();
    List<String> handledButNotFlagged = new ArrayList<>();

    for (String name : names) {
      long tagId = KnownTagCodec.keyOf(name);
      DDSpanContext span = probeSpan();
      interceptor.interceptTag(span, tagId, name, PROBE);

      boolean handled = !fellThroughToDefault(span);
      boolean flagged = KnownTagCodec.isIntercepted(tagId);

      if (flagged && !handled) {
        flaggedButNotHandled.add(name);
      } else if (handled && !flagged) {
        handledButNotFlagged.add(name);
      }
    }

    assertTrue(
        flaggedButNotHandled.isEmpty(),
        "declared `intercepted`/`reserved` in tag-conventions.java.yaml but TagInterceptor has no "
            + "case for them — either add the case or drop the declaration: "
            + flaggedButNotHandled);
    assertTrue(
        handledButNotFlagged.isEmpty(),
        "TagInterceptor routes them but tag-conventions.java.yaml does not declare them, so the "
            + "pre-screen will skip them: "
            + handledButNotFlagged);
  }

  /**
   * The pre-screen must agree with the dispatch for the same reason, and it is the half that runs
   * on every {@code setTag}. A custom tag has no id at all, so it can only be recognised by name.
   */
  @Test
  void preScreenAgreesWithTheFlagAndStillSeesCustomSplitTags() {
    for (String name : allKnownTagNames()) {
      long tagId = KnownTagCodec.keyOf(name);
      assertEquals(
          KnownTagCodec.isIntercepted(tagId),
          interceptorSplittingOn(emptyTags()).needsIntercept(tagId, name),
          name);
    }

    TagInterceptor splitting = interceptorSplittingOn(singleton("my.custom.tag"));
    assertTrue(splitting.needsIntercept("my.custom.tag"), "custom split tag has no id to test");
    assertEquals(0L, KnownTagCodec.keyOf("my.custom.tag"));
    assertFalse(interceptorSplittingOn(emptyTags()).needsIntercept("my.custom.tag"));
  }

  /**
   * {@code keyOf} is many→one, so a tag routes under every name it is known by. This is what
   * replaces the hand-maintained {@code "service.name"}/{@code "service"} pair of case labels, and
   * it now extends to the OpenTelemetry namespace for free.
   */
  @Test
  void alternateNamesRouteToTheSameHandler() {
    assertSameRoute("service", "service.name");
    assertSameRoute("http.method", "http.request.method");
    assertSameRoute("http.status_code", "http.response.status_code");
    assertSameRoute("http.url", "url.full");
    assertSameRoute("db.statement", "db.query.text");
  }

  private static void assertSameRoute(String ddName, String otherName) {
    assertEquals(KnownTagCodec.keyOf(ddName), KnownTagCodec.keyOf(otherName), otherName);

    TagInterceptor interceptor = interceptorSplittingOn(emptyTags());
    assertEquals(
        interceptor.needsIntercept(ddName), interceptor.needsIntercept(otherName), otherName);
  }

  /** The bundle screen is the same test, once per entry, over ids the entries already carry. */
  @Test
  void bundleScreenSeesRoutedEntries() {
    TagInterceptor interceptor = interceptorSplittingOn(emptyTags());

    TagMap plain = TagMap.create();
    plain.set("component", "netty");
    plain.set("http.route", "/x");
    assertFalse(interceptor.needsIntercept(plain));

    TagMap routed = TagMap.create();
    routed.set("component", "netty");
    routed.set("service.name", "billing"); // routed under its OpenTelemetry name
    assertTrue(interceptor.needsIntercept(routed));

    TagMap custom = TagMap.create();
    custom.set("my.custom.tag", "v");
    assertFalse(interceptor.needsIntercept(custom));
    assertTrue(interceptorSplittingOn(singleton("my.custom.tag")).needsIntercept(custom));
  }

  private static Set<String> emptyTags() {
    return new LinkedHashSet<>();
  }

  private static Set<String> singleton(String tag) {
    Set<String> tags = new LinkedHashSet<>();
    tags.add(tag);
    return tags;
  }
}
