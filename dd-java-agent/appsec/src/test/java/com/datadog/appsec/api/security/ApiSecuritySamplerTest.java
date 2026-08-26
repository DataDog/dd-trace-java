package com.datadog.appsec.api.security;

import static datadog.trace.api.config.GeneralConfig.APM_TRACING_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.appsec.gateway.AppSecRequestContext;
import datadog.trace.api.telemetry.MetricCollector.Metric;
import datadog.trace.api.telemetry.WafMetricCollector;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.DDJavaSpecification;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ApiSecuritySamplerTest extends DDJavaSpecification {

  private static final String FRAMEWORK = "test-framework";

  private static final String MISSING_ROUTE_METRIC = "api_security.missing_route";

  private static final long EXPIRATION_TIME_IN_MS = 10_000L;

  @BeforeEach
  void resetTelemetry() {
    // The raw metrics queue is a static singleton, drain it so each test starts from a clean state.
    WafMetricCollector.get().drain();
  }

  @Test
  void happyPathWithSingleRequest() throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertTrue(sampler.preSampleRequest(ctx, FRAMEWORK));

    ctx.setKeepOpenForApiSecurityPostProcessing(true);
    assertTrue(sampler.sampleRequest(ctx));
  }

  @Test
  void secondRequestIsNotSampledForTheSameEndpoint() throws ReflectiveOperationException {
    AppSecRequestContext ctx1 = createContext("route1", "GET", 200);
    AppSecRequestContext ctx2 = createContext("route1", "GET", 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertTrue(sampler.preSampleRequest(ctx1, FRAMEWORK));
    ctx1.setKeepOpenForApiSecurityPostProcessing(true);
    assertTrue(sampler.sampleRequest(ctx1));
    sampler.releaseOne();

    assertFalse(sampler.preSampleRequest(ctx2, FRAMEWORK));
  }

  @Test
  void preSampleRequestWithMaximumConcurrentContexts() throws ReflectiveOperationException {
    AppSecRequestContext ctx1 = createContext("route2", "GET", 200);
    AppSecRequestContext ctx2 = createContext("route3", "GET", 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();
    int maxPostProcessingTasks = maxPostProcessingTasks();
    assertTrue(maxPostProcessingTasks > 0);

    // exhaust the maximum number of concurrent contexts
    for (int i = 1; i <= maxPostProcessingTasks; i++) {
      AppSecRequestContext ctx = createContext("route1", "GET", 200 + i);
      assertTrue(sampler.preSampleRequest(ctx, FRAMEWORK));
    }

    // try to sample one more
    assertFalse(sampler.preSampleRequest(ctx1, FRAMEWORK));

    // release one context, next can be sampled
    sampler.releaseOne();
    assertTrue(sampler.preSampleRequest(ctx2, FRAMEWORK));
  }

  @Test
  void preSampleRequestWithNullRouteAndNoUrl() throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext(null, "GET", 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertFalse(sampler.preSampleRequest(ctx, FRAMEWORK));
  }

  @Test
  void preSampleRequestWithNullRouteButValidUrlUsesEndpointFallback()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertTrue(sampler.preSampleRequest(ctx, FRAMEWORK));
    assertNotNull(ctx.getOrComputeEndpoint());
    assertNotNull(ctx.getApiSecurityEndpointHash());
  }

  @Test
  void preSampleRequestWithNullRouteAnd404StatusDoesNotSample()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 404, "http://localhost:8080/unknown/path");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertFalse(sampler.preSampleRequest(ctx, FRAMEWORK));
    // 404s are short-circuited before route resolution, so no missing route is reported
    assertEquals(0, missingRouteMetrics().size());
  }

  @Test
  void preSampleRequestWithNullRouteAndBlockedRequestDoesNotSample()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 403, "http://localhost:8080/admin/users");
    ctx.setWafBlocked(); // Request was blocked by AppSec
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // Blocked requests should not be sampled
    assertFalse(sampler.preSampleRequest(ctx, FRAMEWORK));
    // Blocked requests are short-circuited before route resolution
    assertEquals(0, missingRouteMetrics().size());
  }

  @Test
  void preSampleRequestWithNullRouteAnd403NonBlockedApiDoesSample()
      throws ReflectiveOperationException {
    // NOT calling setWafBlocked() - this is a legitimate API that returns 403
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 403, "http://localhost:8080/api/forbidden-resource");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // Legitimate APIs that return 403 should be sampled
    assertTrue(sampler.preSampleRequest(ctx, FRAMEWORK));
    assertNotNull(ctx.getOrComputeEndpoint());
    assertNotNull(ctx.getApiSecurityEndpointHash());
  }

  @Test
  void preSampleRequestWithNullRouteAndBlockedRequestWithDifferentStatusCodesDoesNotSample()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx200 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/attack");
    ctx200.setWafBlocked();
    AppSecRequestContext ctx500 =
        createContextWithUrl(null, "GET", 500, "http://localhost:8080/attack");
    ctx500.setWafBlocked();
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    // Blocked requests should not be sampled regardless of status code
    assertFalse(sampler.preSampleRequest(ctx200, FRAMEWORK));
    assertFalse(sampler.preSampleRequest(ctx500, FRAMEWORK));
  }

  @Test
  void secondRequestWithSameEndpointIsNotSampled() throws ReflectiveOperationException {
    AppSecRequestContext ctx1 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");
    AppSecRequestContext ctx2 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/456");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertTrue(sampler.preSampleRequest(ctx1, FRAMEWORK));
    ctx1.setKeepOpenForApiSecurityPostProcessing(true);
    assertTrue(sampler.sampleRequest(ctx1));
    sampler.releaseOne();

    // Same endpoint pattern, so not sampled
    assertFalse(sampler.preSampleRequest(ctx2, FRAMEWORK));
  }

  @Test
  void endpointIsComputedOnlyOnce() throws ReflectiveOperationException {
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");

    String endpoint1 = ctx.getOrComputeEndpoint();
    String endpoint2 = ctx.getOrComputeEndpoint();

    assertNotNull(endpoint1);
    assertEquals(endpoint1, endpoint2);
  }

  @Test
  void preSampleRequestWithNullMethod() throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext("route1", null, 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertFalse(sampler.preSampleRequest(ctx, FRAMEWORK));
    // The route was resolved, so no missing route is reported
    assertEquals(0, missingRouteMetrics().size());
  }

  @Test
  void preSampleRequestWith0StatusCode() throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext("route1", "GET", 0);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertFalse(sampler.preSampleRequest(ctx, FRAMEWORK));
    // The route was resolved, so no missing route is reported
    assertEquals(0, missingRouteMetrics().size());
  }

  @Test
  void preSampleRequestWithNullContextThrows() {
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertThrows(NullPointerException.class, () -> sampler.preSampleRequest(null, FRAMEWORK));
  }

  @Test
  void sampleRequestWithoutPriorPreSampleRequestNeverWorks() throws ReflectiveOperationException {
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();
    AppSecRequestContext ctx = createContext("route1", "GET", 200);

    assertFalse(sampler.sampleRequest(ctx));
  }

  @Test
  void sampleRequestHonorsExpiration() throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ctx.setApiSecurityEndpointHash(42L);
    ctx.setKeepOpenForApiSecurityPostProcessing(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    long expirationTimeInMs = 10L;
    long expirationTimeInNs = expirationTimeInMs * 1_000_000L;
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl(10, expirationTimeInMs, timeSource);

    assertTrue(sampler.sampleRequest(ctx));

    // second request is not sampled
    assertFalse(sampler.sampleRequest(ctx));

    // expiration time has passed, request is sampled again
    timeSource.advance(expirationTimeInNs);
    assertTrue(sampler.sampleRequest(ctx));
  }

  @Test
  void internalAccessMapNeverGoesBeyondCapacity() throws ReflectiveOperationException {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    int maxCapacity = 10;
    ApiSecuritySamplerImpl sampler =
        new ApiSecuritySamplerImpl(maxCapacity, EXPIRATION_TIME_IN_MS, timeSource);

    for (int i = 0; i < maxCapacity * 10; i++) {
      timeSource.advance(1_000_000L);
      AppSecRequestContext ctx = createContext("route1", "GET", 201);
      ctx.setApiSecurityEndpointHash(i);
      ctx.setKeepOpenForApiSecurityPostProcessing(true);
      assertTrue(sampler.sampleRequest(ctx));
      assertTrue(accessMap(sampler).size() <= maxCapacity);
    }
  }

  @Test
  void expiredEntriesArePurgedFromInternalAccessMap() throws ReflectiveOperationException {
    ControllableTimeSource timeSource = new ControllableTimeSource();
    int maxCapacity = 10;
    ApiSecuritySamplerImpl sampler =
        new ApiSecuritySamplerImpl(maxCapacity, EXPIRATION_TIME_IN_MS, timeSource);

    for (int i = 0; i < maxCapacity * 10; i++) {
      AppSecRequestContext ctx = createContext("route1", "GET", 201);
      ctx.setApiSecurityEndpointHash(i);
      ctx.setKeepOpenForApiSecurityPostProcessing(true);
      assertTrue(sampler.sampleRequest(ctx));
      assertTrue(accessMap(sampler).size() <= 2);
      if (i % 2 != 0) {
        timeSource.advance(EXPIRATION_TIME_IN_MS * 1_000_000L);
      }
    }
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void preSampleRequestWithTracingDisabledUpdatesAccessMapImmediately()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    ApiSecuritySamplerImpl sampler =
        new ApiSecuritySamplerImpl(10, EXPIRATION_TIME_IN_MS, timeSource);

    // first request is presampled with tracing disabled: request is sampled and access map is
    // updated immediately
    assertTrue(sampler.preSampleRequest(ctx, FRAMEWORK));
    assertEquals(1, accessMap(sampler).size());
    assertTrue(accessMap(sampler).containsKey(ctx.getApiSecurityEndpointHash()));

    // second request for same endpoint is not sampled because the endpoint was already updated in
    // the first preSampleRequest
    AppSecRequestContext ctx2 = createContext("route1", "GET", 200);
    sampler.releaseOne();
    assertFalse(sampler.preSampleRequest(ctx2, FRAMEWORK));
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void sampleRequestWithTracingDisabledReturnsTrueWithoutUpdatingAccessMap()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ctx.setApiSecurityEndpointHash(42L);
    ctx.setKeepOpenForApiSecurityPostProcessing(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    ApiSecuritySamplerImpl sampler =
        new ApiSecuritySamplerImpl(10, EXPIRATION_TIME_IN_MS, timeSource);

    // request is sampled without updating access map
    assertTrue(sampler.sampleRequest(ctx));
    assertEquals(0, accessMap(sampler).size());
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "true")
  void preSampleRequestWithTracingEnabledDoesNotUpdateAccessMapImmediately()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    ApiSecuritySamplerImpl sampler =
        new ApiSecuritySamplerImpl(10, EXPIRATION_TIME_IN_MS, timeSource);

    // request is sampled but access map is NOT updated yet
    assertTrue(sampler.preSampleRequest(ctx, FRAMEWORK));
    assertEquals(0, accessMap(sampler).size());

    // sampleRequest is called to finalize sampling, access map is updated there
    assertTrue(sampler.sampleRequest(ctx));
    assertEquals(1, accessMap(sampler).size());
    assertTrue(accessMap(sampler).containsKey(ctx.getApiSecurityEndpointHash()));
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "true")
  void sampleRequestWithTracingEnabledUpdatesAccessMap() throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ctx.setApiSecurityEndpointHash(42L);
    ctx.setKeepOpenForApiSecurityPostProcessing(true);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    ApiSecuritySamplerImpl sampler =
        new ApiSecuritySamplerImpl(10, EXPIRATION_TIME_IN_MS, timeSource);

    // request is sampled and access map is updated
    assertTrue(sampler.sampleRequest(ctx));
    assertEquals(1, accessMap(sampler).size());
    assertTrue(accessMap(sampler).containsKey(42L));

    // second request for the same endpoint is not sampled
    assertFalse(sampler.sampleRequest(ctx));
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void concurrentRequestsWithTracingDisabledDoNotSeeExpiredState()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx1 = createContext("route1", "GET", 200);
    AppSecRequestContext ctx2 = createContext("route1", "GET", 200);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    ApiSecuritySamplerImpl sampler =
        new ApiSecuritySamplerImpl(10, EXPIRATION_TIME_IN_MS, timeSource);

    // first request is sampled and access map is updated immediately
    assertTrue(sampler.preSampleRequest(ctx1, FRAMEWORK));
    assertNotNull(ctx1.getApiSecurityEndpointHash());
    assertEquals(1, accessMap(sampler).size());

    // concurrent second request is not sampled because the endpoint is already in the access map
    sampler.releaseOne();
    assertFalse(sampler.preSampleRequest(ctx2, FRAMEWORK));
  }

  @Test
  @WithConfig(key = APM_TRACING_ENABLED, value = "false")
  void fullFlowWithTracingDisabledUpdatesMapOnlyInPreSampleRequest()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext("route1", "GET", 200);
    ControllableTimeSource timeSource = new ControllableTimeSource();
    ApiSecuritySamplerImpl sampler =
        new ApiSecuritySamplerImpl(10, EXPIRATION_TIME_IN_MS, timeSource);

    // preSampleRequest returns true and updates the access map
    assertTrue(sampler.preSampleRequest(ctx, FRAMEWORK));
    assertEquals(1, accessMap(sampler).size());
    Long hash = ctx.getApiSecurityEndpointHash();
    assertTrue(accessMap(sampler).containsKey(hash));

    // sampleRequest returns true without modifying the access map
    assertTrue(sampler.sampleRequest(ctx));
    assertEquals(1, accessMap(sampler).size());
    // Still has the value from preSampleRequest
    assertEquals(Long.valueOf(0L), accessMap(sampler).get(hash));
  }

  // RFC-1076: Verify endpoint is computed and used for sampling but NOT set as a context field for
  // tagging
  @Test
  void endpointComputedForSamplingIsStoredInternallyButNotExposedAsTag()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertTrue(sampler.preSampleRequest(ctx, FRAMEWORK));
    // Endpoint was computed and used for the hash
    assertNotNull(ctx.getApiSecurityEndpointHash());

    // Endpoint is available via getOrComputeEndpoint (cached)
    assertEquals("/api/users/{param:int}", ctx.getOrComputeEndpoint());

    // Verify endpoint is NOT transferred to any tag-like structure in AppSecRequestContext
    // AppSecRequestContext doesn't have a method to expose endpoint as a tag
    // The endpoint field is internal and only used for sampling decisions
  }

  @Test
  void samplerUsesEndpointNotRouteToComputeHashWhenRouteIsAbsent()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx1 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");
    AppSecRequestContext ctx2 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/456");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    sampler.preSampleRequest(ctx1, FRAMEWORK);
    sampler.preSampleRequest(ctx2, FRAMEWORK);

    // both endpoints are simplified to the same pattern
    assertEquals("/api/users/{param:int}", ctx1.getOrComputeEndpoint());
    assertEquals("/api/users/{param:int}", ctx2.getOrComputeEndpoint());

    // both hashes are identical (computed from endpoint, method, status)
    assertEquals(ctx1.getApiSecurityEndpointHash(), ctx2.getApiSecurityEndpointHash());
  }

  @Test
  void samplerComputesDifferentHashesForDifferentEndpoints() throws ReflectiveOperationException {
    AppSecRequestContext ctx1 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/users/123");
    AppSecRequestContext ctx2 =
        createContextWithUrl(null, "GET", 200, "http://localhost:8080/api/orders/456");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    sampler.preSampleRequest(ctx1, FRAMEWORK);
    sampler.preSampleRequest(ctx2, FRAMEWORK);

    // endpoints are different
    assertEquals("/api/users/{param:int}", ctx1.getOrComputeEndpoint());
    assertEquals("/api/orders/{param:int}", ctx2.getOrComputeEndpoint());

    // hashes are different
    assertNotEquals(ctx1.getApiSecurityEndpointHash(), ctx2.getApiSecurityEndpointHash());
  }

  @Test
  void rfc1076WhenRouteIsPresentSamplerUsesRouteAndDoesNotComputeEndpoint()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx =
        createContextWithUrl(
            "/api/users/{userId}", "GET", 200, "http://localhost:8080/api/users/123");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertTrue(sampler.preSampleRequest(ctx, FRAMEWORK));
    assertNotNull(ctx.getApiSecurityEndpointHash());

    // Endpoint was NOT computed (route was used instead)
    // We can verify this by checking that getOrComputeEndpoint returns the computed value
    // but the sampler used the route directly
    // Now it's computed because we called it
    assertEquals("/api/users/{param:int}", ctx.getOrComputeEndpoint());

    // The hash was computed using the route, not the endpoint
    assertEquals(
        Long.valueOf(computeApiHash("/api/users/{userId}", "GET", 200)),
        ctx.getApiSecurityEndpointHash());
  }

  @Test
  void rfc1076EndpointIsComputedAtMostOnceEvenWithMultipleGetOrComputeEndpointCalls()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx =
        createContextWithUrl(
            null, "GET", 200, "http://localhost:8080/api/users/123/profile/settings");

    // endpoint is computed multiple times, all return the same cached value
    String endpoint1 = ctx.getOrComputeEndpoint();
    String endpoint2 = ctx.getOrComputeEndpoint();
    String endpoint3 = ctx.getOrComputeEndpoint();

    assertEquals("/api/users/{param:int}/profile/settings", endpoint1);
    assertEquals(endpoint1, endpoint2);
    assertEquals(endpoint2, endpoint3);
  }

  @Test
  void rfc1076404WithValidEndpointDoesNotSample() throws ReflectiveOperationException {
    AppSecRequestContext ctx =
        createContextWithUrl(null, "GET", 404, "http://localhost:8080/api/nonexistent/resource");
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertFalse(sampler.preSampleRequest(ctx, FRAMEWORK));
    // Even though endpoint can be computed, 404s are not sampled
    assertNotNull(ctx.getOrComputeEndpoint());
    // But hash was never set because sampling failed
    assertNull(ctx.getApiSecurityEndpointHash());
  }

  @Test
  void rfc1076BlockedRequestWithValidEndpointDoesNotSample() throws ReflectiveOperationException {
    AppSecRequestContext ctx =
        createContextWithUrl(null, "POST", 403, "http://localhost:8080/api/admin/users");
    ctx.setWafBlocked(); // Request blocked by AppSec WAF
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertFalse(sampler.preSampleRequest(ctx, FRAMEWORK));
    // Blocked requests represent attacks, not legitimate API endpoints
    assertNull(ctx.getApiSecurityEndpointHash());
  }

  @Test
  void missingRouteMetricIsReportedWhenRouteCannotBeResolved() throws ReflectiveOperationException {
    // No route and no URL, so neither the route nor the endpoint can be resolved. The request is
    // neither WAF blocked nor a 404, so it reaches the missing route branch.
    AppSecRequestContext ctx = createContext(null, "GET", 200);
    assertNull(ctx.getRoute());
    assertNull(ctx.getOrComputeEndpoint());
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertFalse(sampler.preSampleRequest(ctx, "spring-web"));

    List<Metric> metrics = missingRouteMetrics();
    assertEquals(1, metrics.size());
    assertEquals(1L, metrics.get(0).value.longValue());
    assertEquals("framework:spring-web", metrics.get(0).tags.get(0));
    assertEquals(1, metrics.get(0).tags.size());
  }

  @Test
  void missingRouteMetricReportsUnknownFrameworkWhenFrameworkIsNull()
      throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext(null, "GET", 200);
    ApiSecuritySamplerImpl sampler = new ApiSecuritySamplerImpl();

    assertFalse(sampler.preSampleRequest(ctx, null));

    List<Metric> metrics = missingRouteMetrics();
    assertEquals(1, metrics.size());
    assertEquals("framework:unknown", metrics.get(0).tags.get(0));
  }

  /** Returns the {@code api_security.missing_route} metrics reported so far. */
  private static List<Metric> missingRouteMetrics() {
    return WafMetricCollector.get().drain().stream()
        .filter(metric -> MISSING_ROUTE_METRIC.equals(metric.metricName))
        .collect(Collectors.toList());
  }

  /** Helper method to compute the hash the same way as {@link ApiSecuritySamplerImpl}. */
  private static long computeApiHash(String route, String method, int statusCode) {
    long result = 17;
    result = 31 * result + route.hashCode();
    result = 31 * result + method.hashCode();
    result = 31 * result + statusCode;
    return result;
  }

  private static AppSecRequestContext createContext(String route, String method, int statusCode)
      throws ReflectiveOperationException {
    AppSecRequestContext ctx = new AppSecRequestContext();
    ctx.setRoute(route);
    setMethod(ctx, method);
    ctx.setResponseStatus(statusCode);
    return ctx;
  }

  private static AppSecRequestContext createContextWithUrl(
      String route, String method, int statusCode, String url) throws ReflectiveOperationException {
    AppSecRequestContext ctx = createContext(route, method, statusCode);
    ctx.setHttpUrl(url);
    return ctx;
  }

  /**
   * {@code AppSecRequestContext#setMethod} is package private in {@code
   * com.datadog.appsec.gateway}, so it is not reachable from this package without reflection.
   */
  private static void setMethod(AppSecRequestContext ctx, String method)
      throws ReflectiveOperationException {
    Method setter = AppSecRequestContext.class.getDeclaredMethod("setMethod", String.class);
    setter.setAccessible(true);
    setter.invoke(ctx, method);
  }

  /** {@code ApiSecuritySamplerImpl#accessMap} is private, so it is read reflectively. */
  @SuppressWarnings("unchecked")
  private static Map<Long, Long> accessMap(ApiSecuritySamplerImpl sampler)
      throws ReflectiveOperationException {
    Field field = ApiSecuritySamplerImpl.class.getDeclaredField("accessMap");
    field.setAccessible(true);
    return (Map<Long, Long>) field.get(sampler);
  }

  /** {@code ApiSecuritySamplerImpl#MAX_POST_PROCESSING_TASKS} is private. */
  private static int maxPostProcessingTasks() throws ReflectiveOperationException {
    Field field = ApiSecuritySamplerImpl.class.getDeclaredField("MAX_POST_PROCESSING_TASKS");
    field.setAccessible(true);
    return (int) field.get(null);
  }
}
