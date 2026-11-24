package com.datadog.appsec.api.security;

import static com.datadog.appsec.api.security.ApiSecuritySamplerImpl.MAX_POST_PROCESSING_TASKS;
import static com.datadog.appsec.gateway.AppSecRequestContextTestBridge.setMethod;
import static datadog.trace.api.config.GeneralConfig.APM_TRACING_ENABLED;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.api.telemetry.WafMetricCollector.WafMetric;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiSecuritySamplerTest extends DDJavaSpecification {

  private static final String FRAMEWORK = "test-framework";
  private static final String MISSING_ROUTE_METRIC = "api_security.missing_route";
  private static final String USERS_ENDPOINT = "/api/users/{param:int}";

  @BeforeEach
  void setup() {
    // the raw metrics queue and its backing counters are a static singleton, flush and drain
    // so each test starts from a clean state
    WafMetricCollector.get().prepareMetrics();
    WafMetricCollector.get().drain();
  }

  @Test
  void happyPathWithSingleRequest() {
    // given
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertTrue(preSampled);

    // when
    ctx.setKeepOpenForApiSecurityPostProcessing(true);
    boolean sampled = sampler.sampleRequest(ctx);

    // then
    assertTrue(sampled);
  }

  @Test
  void secondRequestIsNotSampledForTheSameEndpoint() {
    // given
    AppSecRequestContext ctx1 = createContext("route1", "GET", 200);
    AppSecRequestContext ctx2 = createContext("route1", "GET", 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled1 = sampler.preSampleRequest(ctx1, FRAMEWORK);
    ctx1.setKeepOpenForApiSecurityPostProcessing(true);
    boolean sampled1 = sampler.sampleRequest(ctx1);
    sampler.releaseOne();

    // then
    assertTrue(preSampled1);
    assertTrue(sampled1);

    // when
    boolean preSampled2 = sampler.preSampleRequest(ctx2, FRAMEWORK);

    // then
    assertFalse(preSampled2);
  }

  @Test
  void preSampleRequestWithMaximumConcurrentContexts() {
    // given
    AppSecRequestContext ctx1 = createContext("route2", "GET", 200);
    AppSecRequestContext ctx2 = createContext("route3", "GET", 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();
    assertTrue(MAX_POST_PROCESSING_TASKS > 0);

    // when: exhaust the maximum number of concurrent contexts
    for (int index = 1; index <= MAX_POST_PROCESSING_TASKS; index++) {
      // then
      assertTrue(sampler.preSampleRequest(createContext("route1", "GET", 200 + index), FRAMEWORK));
    }

    // and: try to sample one more
    boolean preSampled2 = sampler.preSampleRequest(ctx1, FRAMEWORK);

    // then
    assertFalse(preSampled2);

    // when: release one context
    sampler.releaseOne();

    // and: next can be sampled
    boolean preSampled3 = sampler.preSampleRequest(ctx2, FRAMEWORK);

    // then
    assertTrue(preSampled3);
  }

  @Test
  void preSampleRequestWithNullRouteAndNoUrl() {
    // given
    AppSecRequestContext ctx = createContext(null, "GET", 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertFalse(preSampled);
  }

  @Test
  void preSampleRequestWithNullRouteButValidUrlUsesEndpointFallback() {
    // given
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertTrue(preSampled);
    assertNotNull(ctx.getOrComputeEndpoint());
    assertNotNull(ctx.getApiSecurityEndpointHash());
  }

  @Test
  void preSampleRequestWithNullRouteAnd404StatusDoesNotSample() {
    // given
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 404, "http://localhost:8080/unknown/path");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertFalse(preSampled);
  }

  @Test
  void preSampleRequestWithNullRouteAndBlockedRequestDoesNotSample() {
    // given
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 403, "http://localhost:8080/admin/users");
    ctx.setWafBlocked(); // Request was blocked by AppSec
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertFalse(preSampled); // Blocked requests should not be sampled
  }

  @Test
  void preSampleRequestWithNullRouteAnd403NonBlockedApiDoesSample() {
    // given
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 403, "http://localhost:8080/api/forbidden-resource");
    // NOT calling setWafBlocked() - this is a legitimate API that returns 403
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertTrue(preSampled); // Legitimate APIs that return 403 should be sampled
    assertNotNull(ctx.getOrComputeEndpoint());
    assertNotNull(ctx.getApiSecurityEndpointHash());
  }

  @Test
  void preSampleRequestWithNullRouteAndBlockedRequestWithDifferentStatusCodesDoesNotSample() {
    // given
    AppSecRequestContext ctx200 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/attack");
    ctx200.setWafBlocked();
    AppSecRequestContext ctx500 =
        createContextWithUrl(null, "GET", 500, "http://localhost:8080/attack");
    ctx500.setWafBlocked();
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled200 = sampler.preSampleRequest(ctx200, FRAMEWORK);
    boolean preSampled500 = sampler.preSampleRequest(ctx500, FRAMEWORK);

    // then
    // Blocked requests should not be sampled regardless of status code
    assertFalse(preSampled200);
    assertFalse(preSampled500);
  }

  @Test
  void secondRequestWithSameEndpointIsNotSampled() {
    // given
    AppSecRequestContext ctx1 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");
    AppSecRequestContext ctx2 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/456");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled1 = sampler.preSampleRequest(ctx1, FRAMEWORK);
    ctx1.setKeepOpenForApiSecurityPostProcessing(true);
    boolean sampled1 = sampler.sampleRequest(ctx1);
    sampler.releaseOne();

    // then
    assertTrue(preSampled1);
    assertTrue(sampled1);

    // when
    boolean preSampled2 = sampler.preSampleRequest(ctx2, FRAMEWORK);

    // then
    assertFalse(preSampled2); // Same endpoint pattern, so not sampled
  }

  @Test
  void endpointIsComputedOnlyOnce() {
    // given
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");

    // when
    String endpoint1 = ctx.getOrComputeEndpoint();
    String endpoint2 = ctx.getOrComputeEndpoint();

    // then
    assertNotNull(endpoint1);
    assertEquals(endpoint1, endpoint2);
  }

  @Test
  void preSampleRequestWithNullMethod() {
    // given
    AppSecRequestContext ctx = createContext("route1", null, 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertFalse(preSampled);
  }

  @Test
  void preSampleRequestWith0StatusCode() {
    // given
    AppSecRequestContext ctx = createContext("route1", "GET", 0);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertFalse(preSampled);
  }

  @Test
  void sampleRequestWithNullContextThrows() {
    // given
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when / then
    assertThrows(NullPointerException.class, () -> sampler.preSampleRequest(null, FRAMEWORK));
  }

  @Test
  void sampleRequestWithoutPriorPreSampleRequestNeverWorks() {
    // given
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();
    AppSecRequestContext ctx = createContext("route1", "GET", 200);

    // when
    boolean sampled = sampler.sampleRequest(ctx);

    // then
    assertFalse(sampled);
  }

  @Test
  void sampleRequestHonorsExpiration() {
    // given
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ctx.setApiSecurityEndpointHash(42L);
    ctx.setKeepOpenForApiSecurityPostProcessing(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(0);
    long expirationTimeInMs = 10L;
    long expirationTimeInNs = expirationTimeInMs * 1_000_000;
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource);

    // when
    boolean sampled = sampler.sampleRequest(ctx);

    // then
    assertTrue(sampled);

    // when
    sampled = sampler.sampleRequest(ctx);

    // then: second request is not sampled
    assertFalse(sampled);

    // when: expiration time has passed
    timeSource.advance(expirationTimeInNs);
    sampled = sampler.sampleRequest(ctx);

    // then: request is sampled again
    assertTrue(sampled);
  }

  @Test
  void internalAccessMapNeverGoesBeyondCapacity() {
    // given
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(0);
    long expirationTimeInMs = 10_000;
    int maxCapacity = 10;
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource);

    // expect
    for (int index = 0; index < maxCapacity * 10; index++) {
      timeSource.advance(1_000_000);
      AppSecRequestContext ctx = createContext("route1", "GET", 200 + 1);
      ctx.setApiSecurityEndpointHash(index);
      ctx.setKeepOpenForApiSecurityPostProcessing(true);
      assertTrue(sampler.sampleRequest(ctx));
      assertTrue(sampler.accessMap.size() <= maxCapacity);
    }
  }

  @Test
  void expiredEntriesArePurgedFromInternalAccessMap() {
    // given
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(0);
    long expirationTimeInMs = 10_000;
    int maxCapacity = 10;
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource);

    // expect
    for (int index = 0; index < maxCapacity * 10; index++) {
      AppSecRequestContext ctx = createContext("route1", "GET", 200 + 1);
      ctx.setApiSecurityEndpointHash(index);
      ctx.setKeepOpenForApiSecurityPostProcessing(true);
      assertTrue(sampler.sampleRequest(ctx));
      assertTrue(sampler.accessMap.size() <= 2);
      if (index % 2 != 0) {
        timeSource.advance(expirationTimeInMs * 1_000_000);
      }
    }
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void preSampleRequestWithTracingDisabledUpdatesAccessMapImmediately() {
    // given
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ApiSecuritySamplerImpl sampler = createSamplerWithControllableTime();

    // when: first request is presampled with tracing disabled
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then: request is sampled and access map is updated immediately
    assertTrue(preSampled);
    assertEquals(1, sampler.accessMap.size());
    assertTrue(sampler.accessMap.containsKey(ctx.getApiSecurityEndpointHash()));

    // when: second request for same endpoint is attempted
    AppSecRequestContext ctx2 = createContext("route1", "GET", 200);
    sampler.releaseOne();
    boolean preSampled2 = sampler.preSampleRequest(ctx2, FRAMEWORK);

    // then: second request is not sampled because endpoint was already updated in first
    // preSampleRequest
    assertFalse(preSampled2);
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void sampleRequestWithTracingDisabledReturnsTrueWithoutUpdatingAccessMap() {
    // given
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ctx.setApiSecurityEndpointHash(42L);
    ctx.setKeepOpenForApiSecurityPostProcessing(true);
    ApiSecuritySamplerImpl sampler = createSamplerWithControllableTime();

    // when: sampleRequest is called with tracing disabled
    boolean sampled = sampler.sampleRequest(ctx);

    // then: request is sampled without updating access map
    assertTrue(sampled);
    assertEquals(0, sampler.accessMap.size());
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "true")
  void preSampleRequestWithTracingEnabledDoesNotUpdateAccessMapImmediately() {
    // given
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ApiSecuritySamplerImpl sampler = createSamplerWithControllableTime();

    // when: request is presampled with tracing enabled
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then: request is sampled but access map is NOT updated yet
    assertTrue(preSampled);
    assertEquals(0, sampler.accessMap.size());

    // when: sampleRequest is called to finalize sampling
    boolean sampled = sampler.sampleRequest(ctx);

    // then: access map is updated in sampleRequest
    assertTrue(sampled);
    assertEquals(1, sampler.accessMap.size());
    assertTrue(sampler.accessMap.containsKey(ctx.getApiSecurityEndpointHash()));
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "true")
  void sampleRequestWithTracingEnabledUpdatesAccessMap() {
    // given
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ctx.setApiSecurityEndpointHash(42L);
    ctx.setKeepOpenForApiSecurityPostProcessing(true);
    ApiSecuritySamplerImpl sampler = createSamplerWithControllableTime();

    // when: sampleRequest is called with tracing enabled
    boolean sampled = sampler.sampleRequest(ctx);

    // then: request is sampled and access map is updated
    assertTrue(sampled);
    assertEquals(1, sampler.accessMap.size());
    assertTrue(sampler.accessMap.containsKey(42L));

    // when: second request for same endpoint is made
    boolean sampled2 = sampler.sampleRequest(ctx);

    // then: second request is not sampled
    assertFalse(sampled2);
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void concurrentRequestsWithTracingDisabledDoNotSeeExpiredState() {
    // given
    AppSecRequestContext ctx1 = createContext("route1", "GET", 200);
    AppSecRequestContext ctx2 = createContext("route1", "GET", 200);
    ApiSecuritySamplerImpl sampler = createSamplerWithControllableTime();

    // when: first request is presampled
    boolean preSampled1 = sampler.preSampleRequest(ctx1, FRAMEWORK);

    // then: first request is sampled and access map is updated immediately
    assertTrue(preSampled1);
    assertNotNull(ctx1.getApiSecurityEndpointHash());
    assertEquals(1, sampler.accessMap.size());

    // when: concurrent second request tries to presample same endpoint
    sampler.releaseOne();
    boolean preSampled2 = sampler.preSampleRequest(ctx2, FRAMEWORK);

    // then: second request is not sampled because endpoint is already in access map
    assertFalse(preSampled2);
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void fullFlowWithTracingDisabledUpdatesMapOnlyInPreSampleRequest() {
    // given
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ApiSecuritySamplerImpl sampler = createSamplerWithControllableTime();

    // when: request goes through full sampling flow with tracing disabled
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then: preSampleRequest returns true and updates access map
    assertTrue(preSampled);
    assertEquals(1, sampler.accessMap.size());
    Long hash = ctx.getApiSecurityEndpointHash();
    assertTrue(sampler.accessMap.containsKey(hash));

    // when: sampleRequest is called
    boolean sampled = sampler.sampleRequest(ctx);

    // then: sampleRequest returns true without modifying access map
    assertTrue(sampled);
    assertEquals(1, sampler.accessMap.size());
    assertEquals(
        Long.valueOf(0L), sampler.accessMap.get(hash)); // Still has the value from preSampleRequest
  }

  // RFC-1076: Verify endpoint is computed and used for sampling but NOT set as a context field for
  // tagging
  @Test
  void endpointComputedForSamplingIsStoredInternallyButNotExposedAsTag() {
    // given
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertTrue(preSampled);
    // Endpoint was computed and used for the hash
    assertNotNull(ctx.getApiSecurityEndpointHash());

    // Endpoint is available via getOrComputeEndpoint (cached)
    String endpoint = ctx.getOrComputeEndpoint();
    assertNotNull(endpoint);
    assertEquals(USERS_ENDPOINT, endpoint);

    // Verify endpoint is NOT transferred to any tag-like structure in AppSecRequestContext
    // AppSecRequestContext doesn't have a method to expose endpoint as a tag
    // The endpoint field is internal and only used for sampling decisions
  }

  @Test
  void samplerUsesEndpointNotRouteToComputeHashWhenRouteIsAbsent() {
    // given
    AppSecRequestContext ctx1 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");
    AppSecRequestContext ctx2 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/456");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when: first request uses endpoint to compute hash
    sampler.preSampleRequest(ctx1, FRAMEWORK);
    Long hash1 = ctx1.getApiSecurityEndpointHash();
    String endpoint1 = ctx1.getOrComputeEndpoint();

    // and: second request with same endpoint pattern
    sampler.preSampleRequest(ctx2, FRAMEWORK);
    Long hash2 = ctx2.getApiSecurityEndpointHash();
    String endpoint2 = ctx2.getOrComputeEndpoint();

    // then: both endpoints are simplified to the same pattern
    assertEquals(USERS_ENDPOINT, endpoint1);
    assertEquals(USERS_ENDPOINT, endpoint2);

    // and: both hashes are identical (computed from endpoint, method, status)
    assertEquals(hash1, hash2);
  }

  @Test
  void samplerComputesDifferentHashesForDifferentEndpoints() {
    // given
    AppSecRequestContext ctx1 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");
    AppSecRequestContext ctx2 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/orders/456");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    sampler.preSampleRequest(ctx1, FRAMEWORK);
    sampler.preSampleRequest(ctx2, FRAMEWORK);
    Long hash1 = ctx1.getApiSecurityEndpointHash();
    Long hash2 = ctx2.getApiSecurityEndpointHash();
    String endpoint1 = ctx1.getOrComputeEndpoint();
    String endpoint2 = ctx2.getOrComputeEndpoint();

    // then: endpoints are different
    assertEquals(USERS_ENDPOINT, endpoint1);
    assertEquals("/api/orders/{param:int}", endpoint2);

    // and: hashes are different
    assertNotEquals(hash1, hash2);
  }

  @Test
  void rfc1076WhenRouteIsPresentSamplerUsesRouteAndDoesNotComputeEndpoint() {
    // given
    String route = "/api/users/{userId}";
    AppSecRequestContext ctx =
        createContextWithUrl(route, "GET", 200, "http://localhost:8080/api/users/123");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertTrue(preSampled);
    assertNotNull(ctx.getApiSecurityEndpointHash());

    // Endpoint was NOT computed (route was used instead)
    // We can verify this by checking that getOrComputeEndpoint returns the computed value
    // but the sampler used the route directly
    String endpoint = ctx.getOrComputeEndpoint();
    assertEquals(USERS_ENDPOINT, endpoint); // Now it's computed because we called it

    // The hash was computed using the route, not the endpoint
    long hashFromRoute = computeApiHash(route, "GET", 200);
    assertEquals(Long.valueOf(hashFromRoute), ctx.getApiSecurityEndpointHash());
  }

  @Test
  void rfc1076EndpointIsComputedAtMostOnceEvenWithMultipleGetOrComputeEndpointCalls() {
    // given
    AppSecRequestContext ctx =
        createContextWithUrl(
            null, "GET", 200, "http://localhost:8080/api/users/123/profile/settings");

    // when: endpoint is computed multiple times
    String endpoint1 = ctx.getOrComputeEndpoint();
    String endpoint2 = ctx.getOrComputeEndpoint();
    String endpoint3 = ctx.getOrComputeEndpoint();

    // then: all return the same instance (cached)
    assertNotNull(endpoint1);
    assertSame(endpoint1, endpoint2);
    assertSame(endpoint2, endpoint3);
    assertEquals("/api/users/{param:int}/profile/settings", endpoint1);
  }

  @Test
  void rfc1076_404WithValidEndpointDoesNotSample() {
    // given
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 404, "http://localhost:8080/api/nonexistent/resource");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertFalse(preSampled);
    // Even though endpoint can be computed, 404s are not sampled
    String endpoint = ctx.getOrComputeEndpoint();
    assertNotNull(endpoint); // Endpoint is computable
    // But hash was never set because sampling failed
    assertNull(ctx.getApiSecurityEndpointHash());
  }

  @Test
  void rfc1076BlockedRequestWithValidEndpointDoesNotSample() {
    // given
    AppSecRequestContext ctx =
        createContextWithUrl(null, "POST", 403, "http://localhost:8080/api/admin/users");
    ctx.setWafBlocked(); // Request blocked by AppSec WAF
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, FRAMEWORK);

    // then
    assertFalse(preSampled);
    // Blocked requests represent attacks, not legitimate API endpoints
    assertNull(ctx.getApiSecurityEndpointHash());
  }

  @Test
  void missingRouteMetricIsReportedWhenTheRouteCannotBeResolved() {
    // given: no route and no URL, so neither the route nor the endpoint can be resolved
    AppSecRequestContext ctx = createContext(null, "GET", 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // expect: the request is neither WAF blocked nor a 404, so it reaches the missing route branch
    assertNull(ctx.getRoute());
    assertNull(ctx.getOrComputeEndpoint());

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, "spring-web");

    // then
    assertFalse(preSampled);

    // and
    List<WafMetric> metrics = missingRouteMetrics();
    assertEquals(1, metrics.size());
    assertEquals(1, metrics.get(0).value.intValue());
    assertEquals(singletonList("framework:spring-web"), metrics.get(0).tags);
  }

  @Test
  void missingRouteMetricReportsAnUnknownFrameworkWhenTheFrameworkIsNull() {
    // given
    AppSecRequestContext ctx = createContext(null, "GET", 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // when
    boolean preSampled = sampler.preSampleRequest(ctx, null);

    // then
    assertFalse(preSampled);

    // and
    List<WafMetric> metrics = missingRouteMetrics();
    assertEquals(1, metrics.size());
    assertEquals(singletonList("framework:unknown"), metrics.get(0).tags);
  }

  /** Returns the {@code api_security.missing_route} metrics reported so far. */
  private static List<WafMetric> missingRouteMetrics() {
    WafMetricCollector.get().prepareMetrics();
    List<WafMetric> metrics = new ArrayList<>();
    for (WafMetric metric : WafMetricCollector.get().drain()) {
      if (MISSING_ROUTE_METRIC.equals(metric.metricName)) {
        metrics.add(metric);
      }
    }
    return metrics;
  }

  private static ApiSecuritySamplerImpl createSamplerWithControllableTime() {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    timeSource.set(0);
    long expirationTimeInMs = 10_000;
    return new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource);
  }

  // Helper method to compute hash same way as ApiSecuritySamplerImpl
  private static long computeApiHash(String route, String method, int statusCode) {
    long result = 17;
    result = 31 * result + route.hashCode();
    result = 31 * result + method.hashCode();
    result = 31 * result + statusCode;
    return result;
  }

  private static AppSecRequestContext createContext(String route, String method, int statusCode) {
    AppSecRequestContext ctx = new AppSecRequestContext();
    ctx.setRoute(route);
    setMethod(ctx, method);
    ctx.setResponseStatus(statusCode);
    return ctx;
  }

  private static AppSecRequestContext createContextWithUrl(
      String route, String method, int statusCode, String url) {
    AppSecRequestContext ctx = createContext(route, method, statusCode);
    ctx.setHttpUrl(url);
    return ctx;
  }
}
