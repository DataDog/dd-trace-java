package com.datadog.appsec.gateway;

import static datadog.trace.api.telemetry.LogCollector.SEND_TELEMETRY;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.datadog.appsec.ddwaf.WafInitialization;
import com.datadog.appsec.event.data.Address;
import com.datadog.appsec.event.data.KnownAddresses;
import com.datadog.appsec.event.data.MapDataBundle;
import com.datadog.appsec.report.AppSecEvent;
import com.datadog.ddwaf.Waf;
import com.datadog.ddwaf.WafBuilder;
import com.datadog.ddwaf.WafContext;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.api.Config;
import datadog.trace.api.internal.TraceSegment;
import datadog.trace.test.logging.CapturedLog;
import datadog.trace.test.logging.TestLogCollector;
import datadog.trace.util.stacktrace.StackTraceEvent;
import datadog.trace.util.stacktrace.StackTraceFrame;
import java.io.Closeable;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AppSecRequestContextTest {

  private static final JsonAdapter<Map<String, Object>> ADAPTER =
      new Moshi.Builder()
          .build()
          .adapter(Types.newParameterizedType(Map.class, String.class, Object.class));

  private final AppSecRequestContext ctx = new AppSecRequestContext();
  private WafBuilder wafBuilder;

  @AfterEach
  void tearDown() {
    if (wafBuilder != null) {
      wafBuilder.close();
    }
  }

  @Test
  void implementsDataBundle() {
    ctx.addAll(MapDataBundle.of(KnownAddresses.REQUEST_URI_RAW, "/a"));

    assertEquals(1, ctx.size());
    assertEquals("/a", ctx.get(KnownAddresses.REQUEST_URI_RAW));
    assertEquals(
        singletonList(KnownAddresses.REQUEST_URI_RAW), new ArrayList<>(ctx.getAllAddresses()));
    assertTrue(ctx.hasAddress(KnownAddresses.REQUEST_URI_RAW));

    Iterator<Map.Entry<Address<?>, Object>> iterator = ctx.iterator();

    assertTrue(iterator.hasNext());

    Map.Entry<Address<?>, Object> element = iterator.next();

    assertEquals(KnownAddresses.REQUEST_URI_RAW, element.getKey());
    assertEquals("/a", element.getValue());
  }

  @Test
  void itIsCloseable() {
    assertInstanceOf(Closeable.class, ctx);

    // close() on a context that was never used must not throw.
    ctx.close();
  }

  @Test
  void addingHeadersAfterTheyAreSaidToBeFinishedIsForbidden() {
    ctx.finishRequestHeaders();

    assertTrue(ctx.isFinishedRequestHeaders());
    assertThrows(IllegalStateException.class, () -> ctx.addRequestHeader("a", "b"));
    assertThrows(
        IllegalStateException.class, () -> ctx.addCookies(singletonMap("a", singletonList("b"))));
  }

  @Test
  void settingUriASecondTimeIsIgnoredFirstValueWins() {
    ctx.setRawURI("/a");
    ctx.setRawURI("/b");

    assertEquals("/a", ctx.getSavedRawURI());
  }

  @Test
  void savesCookiesAndOtherHeaders() {
    ctx.addCookies(singletonMap("a", singletonList("c")));
    ctx.addRequestHeader("user-agent", "foo");

    assertEquals(singletonList("foo"), ctx.getRequestHeaders().get("user-agent"));
    assertEquals(singletonMap("a", singletonList("c")), ctx.getCookies());
  }

  @Test
  void canSaveTheUri() {
    // The Groovy original assigned the private savedRawURI field; setRawURI is its only writer.
    ctx.setRawURI("/a");

    assertEquals("/a", ctx.getSavedRawURI());
  }

  @Test
  void canCollectEvents() {
    ctx.reportEvents(asList(new AppSecEvent(), new AppSecEvent()));
    Collection<AppSecEvent> events = ctx.transferCollectedEvents();

    assertEquals(2, events.size());
    for (AppSecEvent event : events) {
      assertNotNull(event);
    }

    ctx.reportEvents(singletonList(new AppSecEvent()));
    events = ctx.transferCollectedEvents();

    assertEquals(1, events.size());
    assertNotNull(events.iterator().next());
  }

  @Test
  void collectEventsWhenNoneReported() {
    assertTrue(ctx.transferCollectedEvents().isEmpty());
  }

  @Test
  void canCollectStackTraces() {
    StackTraceElement element = new StackTraceElement("class", "method", "file", 1);
    StackTraceFrame frame = new StackTraceFrame(1, element);
    StackTraceEvent event = new StackTraceEvent(singletonList(frame), "java", "id", "message");

    ctx.reportStackTrace(event);
    List<StackTraceEvent> result = ctx.getStackTraces();

    assertEquals(1, result.size());
    StackTraceEvent reportedEvent = result.get(0);
    assertEquals("id", reportedEvent.getId());
    assertEquals("message", reportedEvent.getMessage());
    assertEquals("java", reportedEvent.getLanguage());
    assertEquals(1, reportedEvent.getFrames().size());
    StackTraceFrame reportedFrame = reportedEvent.getFrames().get(0);
    assertEquals(1, reportedFrame.getId());
    assertEquals("class.method(file:1)", reportedFrame.getText());
    assertEquals("file", reportedFrame.getFile());
    assertEquals(Integer.valueOf(1), reportedFrame.getLine());
    assertEquals("class", reportedFrame.getClass_name());
    assertEquals("method", reportedFrame.getFunction());
  }

  @Test
  void collectStackTracesWhenNoneReported() {
    assertNull(ctx.getStackTraces());
  }

  @ParameterizedTest(name = "{0} allow list contains only lowercase names")
  @MethodSource("headersAllowListShouldContainOnlyLowercaseNamesArguments")
  void headersAllowListShouldContainOnlyLowercaseNames(String name, Set<String> headers) {
    for (String header : headers) {
      assertEquals(
          header.toLowerCase(Locale.ROOT),
          header,
          "REASON: Allow header name \"" + header + "\" MUST be lowercase");
    }
  }

  private static Stream<Arguments> headersAllowListShouldContainOnlyLowercaseNamesArguments() {
    return Stream.of(
        arguments(
            "Default request headers", AppSecRequestContext.DEFAULT_REQUEST_HEADERS_ALLOW_LIST),
        arguments("Request headers", AppSecRequestContext.REQUEST_HEADERS_ALLOW_LIST),
        arguments("Response headers", AppSecRequestContext.RESPONSE_HEADERS_ALLOW_LIST));
  }

  @Test
  void basicHeadersCollectionTest() {
    ctx.addRequestHeader("Host", "127.0.0.1");
    ctx.addRequestHeader("Content-Type", "text/html; charset=UTF-8");
    ctx.addRequestHeader("Custom-Header", "value1");
    ctx.addRequestHeader("Accept", "application/json");

    Map<String, List<String>> expected = new HashMap<>();
    expected.put("host", singletonList("127.0.0.1"));
    expected.put("content-type", singletonList("text/html; charset=UTF-8"));
    expected.put("custom-header", singletonList("value1"));
    expected.put("accept", singletonList("application/json"));
    assertEquals(expected, ctx.getRequestHeaders());
  }

  @Test
  void nullHeadersShouldBeIgnored() {
    ctx.addRequestHeader(null, "value");
    ctx.addRequestHeader("key", null);

    assertTrue(ctx.getRequestHeaders().isEmpty());
  }

  @Test
  void concatMultipleValuesForSameHeader() {
    ctx.addRequestHeader("Custom-Header", "value1");
    ctx.addRequestHeader("CUSTOM-HEADER", "value2");
    ctx.addRequestHeader("Accept", "application/json");
    ctx.addRequestHeader("accept", "application/xml");

    Map<String, List<String>> expected = new HashMap<>();
    expected.put("custom-header", asList("value1", "value2"));
    expected.put("accept", asList("application/json", "application/xml"));
    assertEquals(expected, ctx.getRequestHeaders());
  }

  private WafContext createWafContext(AppSecRequestContext context) throws Exception {
    assertTrue(WafInitialization.ONLINE, "libddwaf must be available for this test");
    Waf.initialize(false);
    if (wafBuilder != null) {
      wafBuilder.close();
    }
    wafBuilder = new WafBuilder();
    try (InputStream stream =
        getClass().getClassLoader().getResourceAsStream("test_multi_config.json")) {
      wafBuilder.addOrUpdateConfig("test", ADAPTER.fromJson(Okio.buffer(Okio.source(stream))));
    }
    return context.getOrCreateWafContext(wafBuilder.buildWafHandleInstance(), false, false);
  }

  @Test
  void closeClosesTheWafContext() throws Exception {
    WafContext wafContext = createWafContext(ctx);

    ctx.close();

    // isWafContextClosed() is the public witness for the nulled wafContext field.
    assertTrue(ctx.isWafContextClosed());
    assertFalse(wafContext.isOnline());
  }

  @Test
  void testIsThrottled() {
    RateLimiter rateLimiter = mock(RateLimiter.class);
    when(rateLimiter.isThrottled()).thenReturn(true);
    AppSecRequestContext appSecRequestContext = new AppSecRequestContext();

    // rate limiter is called and throttled is set
    boolean result = appSecRequestContext.isThrottled(rateLimiter);

    assertTrue(result);

    // rate limiter is not called more than once per appsec context, returns first result
    boolean secondResult = appSecRequestContext.isThrottled(rateLimiter);

    assertEquals(result, secondResult);
    verify(rateLimiter, times(1)).isThrottled();
  }

  @Test
  void testThatInternalDataIsReleasedOnClose() throws Exception {
    AppSecRequestContext context = new AppSecRequestContext();

    context.addRequestHeader("Accept", "*");
    context.addResponseHeader("Content-Type", "text/plain");
    context.addCookies(singletonMap("cookie", singletonList("test")));
    context.addAll(MapDataBundle.of(KnownAddresses.REQUEST_METHOD, "GET"));
    // Use reportDerivatives to properly set values via AtomicReference
    context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "test_value")));
    WafContext wafContext = createWafContext(context);
    context.close();

    assertTrue(context.isWafContextClosed());
    assertFalse(wafContext.isOnline());
    // Check that derivatives AtomicReference contains null after close
    assertNull(derivativesField(context).get());

    // Field access, not the getter: it null-coalesces, so emptiness would prove nothing.
    assertNull(requestHeadersField(context));
    assertNull(responseHeadersField(context));
    assertTrue(context.getCookies().isEmpty());
    assertEquals(0, context.size());
  }

  @Test
  void testIncreaseAndGetWafTimeouts() {
    ctx.increaseWafTimeouts();
    ctx.increaseWafTimeouts();

    assertEquals(2, ctx.getWafTimeouts());
  }

  @Test
  void testIncreaseAndGetRaspTimeouts() {
    ctx.increaseRaspTimeouts();
    ctx.increaseRaspTimeouts();

    assertEquals(2, ctx.getRaspTimeouts());
  }

  @Test
  void closeLogsIfRequestEndWasNotCalled() {
    TestLogCollector.enable();
    try {
      AppSecRequestContext context = new AppSecRequestContext();

      context.close();

      CapturedLog releaseLog = null;
      for (CapturedLog captured : TestLogCollector.drainCapturedLogs()) {
        if (captured.message.contains("Request end event was not called before close")) {
          releaseLog = captured;
          break;
        }
      }
      assertNotNull(releaseLog);
      assertEquals(SEND_TELEMETRY, releaseLog.marker);
    } finally {
      TestLogCollector.disable();
    }
  }

  @Test
  void testThatProcessedAttributesAreClearedOnClose() {
    Map<String, Object> derivatives = new HashMap<>();
    derivatives.put("numeric", "42");
    derivatives.put("string", "value");

    ctx.reportDerivatives(derivatives);
    ctx.close();

    assertTrue(ctx.getDerivativeKeys().isEmpty());
  }

  @Test
  void testAttributeHandlingWithLiteralValuesAndRequestDataExtraction() {
    AppSecRequestContext context = new AppSecRequestContext();
    context.setMethod("POST");
    context.setScheme("https");
    context.setRawURI("/api/test");
    context.setRoute("/api/{param}");
    context.setResponseStatus(200);
    context.addRequestHeader("user-agent", "TestAgent/1.0");
    context.addRequestHeader("content-type", "application/json");

    // Test data for attributes
    Map<String, Object> attributes = new HashMap<>();
    attributes.put("_dd.appsec.s.res.headers", literalValue("literal-header-value"));
    attributes.put("_dd.appsec.s.res.method", addressValue("server.request.method"));
    attributes.put("_dd.appsec.s.res.scheme", addressValue("server.request.scheme"));
    attributes.put("_dd.appsec.s.res.uri", addressValue("server.request.uri.raw"));
    attributes.put("_dd.appsec.s.res.route", addressValue("server.request.route"));
    attributes.put("_dd.appsec.s.res.status", addressValue("server.response.status"));
    attributes.put("_dd.appsec.s.res.user_agent", headerValue("user-agent", null));
    attributes.put("_dd.appsec.s.res.content_type", headerValue("content-type", null));
    attributes.put("_dd.appsec.s.res.user_agent_lower", headerValue("user-agent", "lowercase"));
    attributes.put("_dd.appsec.s.res.content_type_upper", headerValue("content-type", "uppercase"));

    context.reportDerivatives(attributes);
    Set<String> keys = context.getDerivativeKeys();

    assertEquals(new HashSet<>(attributes.keySet()), keys);
  }

  @Test
  void testAttributeHandlingWithUnknownAddress() {
    AppSecRequestContext context = new AppSecRequestContext();
    Map<String, Object> attributes =
        singletonMap("_dd.appsec.s.res.unknown", addressValue("server.request.unknown"));

    context.reportDerivatives(attributes);
    Set<String> keys = context.getDerivativeKeys();

    // No attributes should be added for unknown addresses
    assertTrue(keys.isEmpty());
  }

  @Test
  void testAttributeHandlingWithInvalidKeyPath() {
    AppSecRequestContext context = new AppSecRequestContext();
    context.addRequestHeader("user-agent", "TestAgent/1.0");
    Map<String, Object> attributes =
        singletonMap("_dd.appsec.s.res.invalid", headerValue("non-existent-header", null));

    context.reportDerivatives(attributes);
    Set<String> keys = context.getDerivativeKeys();

    // No attributes should be added for invalid key paths
    assertTrue(keys.isEmpty());
  }

  @Test
  void testSamplingOfRequests() {
    int maxRequests = Config.get().getApiSecurityMaxDownstreamRequestBodyAnalysis();
    AppSecRequestContext context = new AppSecRequestContext();
    Random random = new Random();
    Map<Long, Boolean> sampledByRequestId = new HashMap<>();

    for (int request = 0; request <= maxRequests; request++) {
      long requestId = random.nextLong();
      sampledByRequestId.put(requestId, context.sampleHttpClientRequest(requestId));
    }

    int sampledCount = 0;
    for (Boolean sampled : sampledByRequestId.values()) {
      if (sampled) {
        sampledCount++;
      }
    }
    assertEquals(maxRequests, sampledCount);
    for (Map.Entry<Long, Boolean> entry : sampledByRequestId.entrySet()) {
      assertEquals(entry.getValue(), context.isHttpClientRequestSampled(entry.getKey()));
    }
  }

  @Test
  void testCommitDerivativesWithDifferentValueTypes() {
    AppSecRequestContext context = new AppSecRequestContext();
    TraceSegment traceSegment = mock(TraceSegment.class);
    // Capture what setTagTop is being called with
    Map<String, Object> committedTags = captureCommittedTags(traceSegment);

    // Set up derivatives with different types
    Map<String, Object> derivatives = new HashMap<>();
    derivatives.put("numeric_int", literalValue(42));
    derivatives.put("numeric_double", literalValue(3.14));
    derivatives.put("string_value", literalValue("test_string"));
    derivatives.put("boolean_true", literalValue(true));
    derivatives.put("boolean_false", literalValue(false));
    derivatives.put("numeric_string", literalValue("100"));
    derivatives.put("double_string", literalValue("99.5"));
    derivatives.put("non_numeric_string", literalValue("not_a_number"));

    context.reportDerivatives(derivatives);
    boolean result = context.commitDerivatives(traceSegment);

    assertTrue(result);
    assertEquals(8, committedTags.size());

    // Verify numeric values are preserved as numbers
    assertEquals(42, committedTags.get("numeric_int"));
    assertInstanceOf(Integer.class, committedTags.get("numeric_int"));

    // Unlike Groovy, where 3.14 is a BigDecimal, Java gives a Double - both are Numbers
    assertEquals(3.14, committedTags.get("numeric_double"));
    assertInstanceOf(Number.class, committedTags.get("numeric_double"));

    // Verify strings are handled correctly
    assertEquals("test_string", committedTags.get("string_value"));
    assertInstanceOf(String.class, committedTags.get("string_value"));

    // Verify booleans are preserved
    assertEquals(true, committedTags.get("boolean_true"));
    assertInstanceOf(Boolean.class, committedTags.get("boolean_true"));

    assertEquals(false, committedTags.get("boolean_false"));
    assertInstanceOf(Boolean.class, committedTags.get("boolean_false"));

    // Verify numeric strings are converted to numbers
    assertEquals(100L, committedTags.get("numeric_string"));
    assertInstanceOf(Long.class, committedTags.get("numeric_string"));

    assertEquals(99.5, committedTags.get("double_string"));
    assertInstanceOf(Number.class, committedTags.get("double_string"));

    // Verify non-numeric strings remain strings
    assertEquals("not_a_number", committedTags.get("non_numeric_string"));
    assertInstanceOf(String.class, committedTags.get("non_numeric_string"));
  }

  @Test
  void testCommitDerivativesClearsDerivativesAtomically() {
    AppSecRequestContext context = new AppSecRequestContext();
    TraceSegment traceSegment = mock(TraceSegment.class);
    Map<String, Object> derivatives = new HashMap<>();
    derivatives.put("attr1", literalValue("value1"));
    derivatives.put("attr2", literalValue("value2"));

    context.reportDerivatives(derivatives);

    assertEquals(2, context.getDerivativeKeys().size());

    context.commitDerivatives(traceSegment);

    // Derivatives should be cleared after commit
    assertTrue(context.getDerivativeKeys().isEmpty());
    assertNull(derivativesField(context).get());
  }

  @Test
  void testCommitDerivativesWithNullTraceSegmentReturnsFalse() {
    AppSecRequestContext context = new AppSecRequestContext();

    assertFalse(context.commitDerivatives(null));
  }

  @Test
  void testMultipleReportDerivativesCallsAccumulateValues() {
    AppSecRequestContext context = new AppSecRequestContext();

    // First report
    Map<String, Object> firstReport = new HashMap<>();
    firstReport.put("attr1", literalValue("value1"));
    firstReport.put("attr2", literalValue("value2"));
    context.reportDerivatives(firstReport);

    assertEquals(new HashSet<>(asList("attr1", "attr2")), context.getDerivativeKeys());

    // Second report - should accumulate
    Map<String, Object> secondReport = new HashMap<>();
    secondReport.put("attr3", literalValue("value3"));
    secondReport.put("attr4", literalValue("value4"));
    context.reportDerivatives(secondReport);

    assertEquals(
        new HashSet<>(asList("attr1", "attr2", "attr3", "attr4")), context.getDerivativeKeys());
  }

  @Test
  void testMultipleReportDerivativesWithOverlappingKeysUsesLastValue() {
    AppSecRequestContext context = new AppSecRequestContext();
    TraceSegment traceSegment = mock(TraceSegment.class);
    Map<String, Object> committedTags = captureCommittedTags(traceSegment);

    // First report
    context.reportDerivatives(singletonMap("attr1", literalValue("first_value")));
    // Second report with same key - should overwrite
    context.reportDerivatives(singletonMap("attr1", literalValue("second_value")));
    context.commitDerivatives(traceSegment);

    assertEquals("second_value", committedTags.get("attr1"));
  }

  @Test
  void testReportDerivativesMaintainsDataIntegrityUnderConcurrentAccess() throws Exception {
    AppSecRequestContext context = new AppSecRequestContext();
    int numThreads = 3;
    CountDownLatch startLatch = new CountDownLatch(1);

    // Simulate concurrent updates from multiple threads
    ExecutorService executorService = Executors.newFixedThreadPool(numThreads);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int thread = 0; thread < numThreads; thread++) {
        int firstAttribute = thread * 2 + 1;
        futures.add(
            executorService.submit(
                () -> {
                  startLatch.await(); // Wait for all threads to be ready
                  context.reportDerivatives(
                      singletonMap(
                          "attr" + firstAttribute, literalValue("value" + firstAttribute)));
                  context.reportDerivatives(
                      singletonMap(
                          "attr" + (firstAttribute + 1),
                          literalValue("value" + (firstAttribute + 1))));
                  return null;
                }));
      }

      // Release all threads at once to maximize concurrent execution
      startLatch.countDown();

      // Wait for all tasks to complete using the futures
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executorService.shutdown();
    }

    // Verify all attributes were added despite concurrent access
    Set<String> keys = context.getDerivativeKeys();
    // At least the 6 we explicitly added
    assertTrue(keys.size() >= 6, "expected at least 6 derivative keys, got " + keys);
    for (int attribute = 1; attribute <= 6; attribute++) {
      assertTrue(keys.contains("attr" + attribute), "Missing attribute: attr" + attribute);
    }
  }

  @ParameterizedTest(name = "numeric conversion with edge cases: {0}")
  @MethodSource("testNumericConversionWithEdgeCasesArguments")
  void testNumericConversionWithEdgeCases(
      String description, String inputValue, Object expectedValue) {
    AppSecRequestContext context = new AppSecRequestContext();
    TraceSegment traceSegment = mock(TraceSegment.class);
    Map<String, Object> committedTags = captureCommittedTags(traceSegment);

    context.reportDerivatives(singletonMap("test_attr", literalValue(inputValue)));
    context.commitDerivatives(traceSegment);

    // When conversion fails (expectedValue is null), the original string value should be preserved
    // When conversion succeeds, the converted numeric value should be used
    assertEquals(
        expectedValue != null ? expectedValue : inputValue, committedTags.get("test_attr"));
  }

  private static Stream<Arguments> testNumericConversionWithEdgeCasesArguments() {
    return Stream.of(
        // Valid integers
        arguments("zero", "0", 0L),
        arguments("positive integer", "42", 42L),
        arguments("negative integer", "-100", -100L),
        arguments("integer with plus sign", "+999", 999L),
        arguments("large valid long", "9223372036854775807", 9223372036854775807L),
        arguments("large negative long", "-9223372036854775808", -9223372036854775808L),

        // Valid decimals
        arguments("simple decimal", "3.14", 3.14d),
        arguments("negative decimal", "-0.5", -0.5d),
        arguments("decimal with plus sign", "+99.99", 99.99d),
        arguments("zero decimal", "0.0", 0.0d),
        arguments("decimal with many digits", "123.456789", 123.456789d),

        // Whitespace handling (should now parse after trim - issue #10494 fix)
        arguments("leading whitespace integer", " 42", 42L),
        arguments("trailing whitespace integer", "42 ", 42L),
        arguments("both whitespace integer", " 42 ", 42L),
        arguments("tab and newline whitespace", "\t100\n", 100L),
        arguments("multiple spaces decimal", "  -3.14  ", -3.14d),

        // Empty and null
        arguments("null value", null, null),
        arguments("empty string", "", null),
        arguments("whitespace only", "   ", null),
        arguments("tab only", "\t", null),

        // Invalid formats (should return null, original string preserved)
        arguments("alphabetic string", "abc", null),
        arguments("alphanumeric string", "12x34", null),
        arguments("multiple decimals", "3.14.15", null),
        arguments("multiple signs", "+-5", null),
        arguments("sign in middle", "12-34", null),

        // Sign-only strings
        arguments("plus sign only", "+", null),
        arguments("minus sign only", "-", null),
        arguments("plus with whitespace", " + ", null),

        // Overflow cases (should return null gracefully)
        arguments("long overflow positive", "9223372036854775808", null),
        arguments("long overflow negative", "-9223372036854775809", null),
        arguments("very large number", "99999999999999999999999", null),

        // Scientific notation (now supported for backward compatibility)
        arguments("scientific notation lowercase", "1e10", 1.0e10d),
        arguments("scientific notation uppercase", "1E10", 1.0E10d),
        arguments("scientific with decimal", "1.5e10", 1.5e10d),
        arguments("scientific negative exponent", "3.5E-7", 3.5E-7d),
        arguments("scientific with sign", "-2.5e+3", -2.5e+3d),
        arguments("scientific integer base", "5e3", 5000.0d),

        // Exotic number formats (not supported)
        arguments("hexadecimal", "0x10", null),
        arguments("binary", "0b1010", null),
        arguments("octal", "0777", 777L),

        // Edge decimal cases (note: .5 and 5. are valid Java double literals)
        arguments("decimal starts with dot", ".5", 0.5d),
        arguments("decimal ends with dot", "5.", 5.0d),
        arguments("dot only", ".", null),
        arguments("multiple dots", "...", null),

        // Regression - ensure existing valid formats still work
        arguments("regression valid integer", "100", 100L),
        arguments("regression valid decimal", "99.5", 99.5d),
        arguments("regression negative", "-50", -50L),

        // Special characters
        arguments("comma separator", "1,000", null),
        arguments("underscore separator", "1_000", null),
        arguments("currency symbol", "$100", null),
        arguments("percentage", "50%", null),

        // Edge cases with zeros
        arguments("zero with plus", "+0", 0L),
        arguments("zero with minus", "-0", 0L),
        arguments("decimal zero variations", "0.00", 0.0d));
  }

  @Test
  void testGetDerivativeKeysWithEmptyDerivatives() {
    AppSecRequestContext context = new AppSecRequestContext();

    assertTrue(context.getDerivativeKeys().isEmpty());
  }

  @Test
  void testReportDerivativesWithExtractedValuesFromRequestData() {
    AppSecRequestContext context = new AppSecRequestContext();
    context.setMethod("POST");
    context.addRequestHeader("x-custom-header", "custom_value");

    Map<String, Object> derivatives = new HashMap<>();
    derivatives.put("extracted_method", addressValue("server.request.method"));
    derivatives.put("extracted_header", headerValue("x-custom-header", null));

    context.reportDerivatives(derivatives);
    Set<String> keys = context.getDerivativeKeys();

    assertEquals(new HashSet<>(asList("extracted_method", "extracted_header")), keys);
  }

  @Test
  void testReportDerivativesWithTransformers() {
    AppSecRequestContext context = new AppSecRequestContext();
    context.addRequestHeader("user-agent", "Mozilla/5.0");

    Map<String, Object> derivatives = new HashMap<>();
    derivatives.put("ua_lowercase", headerValue("user-agent", "lowercase"));
    derivatives.put("ua_uppercase", headerValue("user-agent", "uppercase"));

    context.reportDerivatives(derivatives);
    Set<String> keys = context.getDerivativeKeys();

    assertEquals(new HashSet<>(asList("ua_lowercase", "ua_uppercase")), keys);
  }

  /** A derivative schema holding a literal value, reported as-is. */
  private static Map<String, Object> literalValue(Object value) {
    return singletonMap("value", value);
  }

  /** A derivative schema extracting the whole value stored at the given address. */
  private static Map<String, Object> addressValue(String address) {
    return singletonMap("address", address);
  }

  /**
   * A derivative schema extracting one request header, optionally through a transformer.
   *
   * @param transformer the transformer to apply, or {@code null} for none
   */
  private static Map<String, Object> headerValue(String header, String transformer) {
    Map<String, Object> schema = new HashMap<>();
    schema.put("address", "server.request.headers");
    schema.put("key_path", singletonList(header));
    if (transformer != null) {
      schema.put("transformers", singletonList(transformer));
    }
    return schema;
  }

  /** Records every {@code setTagTop} call on the mock into the returned map. */
  private static Map<String, Object> captureCommittedTags(TraceSegment traceSegment) {
    Map<String, Object> committedTags = new HashMap<>();
    doAnswer(
            invocation -> {
              committedTags.put(invocation.getArgument(0), invocation.getArgument(1));
              return null;
            })
        .when(traceSegment)
        .setTagTop(anyString(), any());
    return committedTags;
  }

  /**
   * Tests that {@link AppSecRequestContext#close()} releases the request/response header maps and
   * the published derivatives map by replacing references rather than mutating them in place, so
   * that a reader on the trace-processing thread cannot observe a collection being emptied
   * mid-iteration (APPSEC-70134).
   *
   * <p>The assertions read the private fields reflectively on purpose: the invariant is about which
   * instance the context still points at, and the accessors deliberately hide that (the header
   * getters null-coalesce, and {@code getDerivativeKeys} exposes only a key view).
   */
  @Nested
  class ReleaseOnClose {

    @Test
    void closeDoesNotMutateThePublishedDerivativesMap() {
      AppSecRequestContext context = new AppSecRequestContext();
      context.reportDerivatives(singletonMap("test_attr", singletonMap("value", "test_value")));
      Map<String, Object> published = derivativesField(context).get();
      assertEquals(singleton("test_attr"), published.keySet());

      context.close();

      // The reference is detached, but the instance a concurrent reader holds is untouched.
      assertNull(derivativesField(context).get());
      assertEquals(singleton("test_attr"), published.keySet());
      assertEquals("test_value", published.get("test_attr"));
    }

    @Test
    void closeReleasesTheHeaderMapsWithoutMutatingTheReaderSnapshots() {
      AppSecRequestContext context = new AppSecRequestContext();
      context.addRequestHeader("accept", "*");
      context.addResponseHeader("content-type", "text/plain");
      Map<String, List<String>> requestHeaders = context.getRequestHeaders();
      Map<String, List<String>> responseHeaders = context.getResponseHeaders();

      context.close();

      // Readers holding these maps (e.g. async schema extraction) still see their contents.
      assertEquals(singletonMap("accept", singletonList("*")), requestHeaders);
      assertEquals(singletonMap("content-type", singletonList("text/plain")), responseHeaders);

      // The context dropped its own reference, so the contents are collectable.
      assertNull(requestHeadersField(context));
      assertNull(responseHeadersField(context));
    }

    @Test
    void closeReleasesTheHeaderMapsWithoutAllocatingReplacements() {
      AppSecRequestContext context = new AppSecRequestContext();

      // An untouched context holds no header maps at all.
      assertNull(requestHeadersField(context));
      assertNull(responseHeadersField(context));

      context.addRequestHeader("accept", "*");
      context.addResponseHeader("content-type", "text/plain");
      // close() runs twice per request: onRequestEnded, then onRootSpanPublished.
      context.close();
      context.close();

      assertNull(requestHeadersField(context));
      assertNull(responseHeadersField(context));
      // Readers see the shared empty map rather than a freshly allocated one.
      assertSame(emptyMap(), context.getRequestHeaders());
      assertSame(emptyMap(), context.getResponseHeaders());
    }

    @Test
    void addressExtractionAfterCloseDoesNotRematerialiseTheHeaderMaps() {
      AppSecRequestContext context = new AppSecRequestContext();
      context.addRequestHeader("user-agent", "foo");
      context.close();

      // A late WAF result extracts an attribute from a header address.
      context.reportDerivatives(
          singletonMap("_dd.appsec.s.res.user_agent", headerValue("user-agent", null)));

      // The address lookup read the released field instead of allocating a new map.
      assertNull(requestHeadersField(context));
      assertSame(emptyMap(), context.getRequestHeaders());
      assertTrue(context.getDerivativeKeys().isEmpty());
    }

    @Test
    void headersCanStillBeRecordedAfterClose() {
      AppSecRequestContext context = new AppSecRequestContext();
      context.close();

      // A late header arrives after the maps were released; it is accepted, as it was when close()
      // cleared the maps in place.
      context.addRequestHeader("accept", "*");
      context.addResponseHeader("content-type", "text/plain");

      assertEquals(singletonMap("accept", singletonList("*")), context.getRequestHeaders());
      assertEquals(
          singletonMap("content-type", singletonList("text/plain")), context.getResponseHeaders());
    }

    @Test
    void reportDerivativesPublishesANewMapAndLeavesEarlierSnapshotsUntouched() {
      AppSecRequestContext context = new AppSecRequestContext();
      context.reportDerivatives(singletonMap("attr1", literalValue("value1")));
      Map<String, Object> first = derivativesField(context).get();

      context.reportDerivatives(singletonMap("attr2", literalValue("value2")));
      Map<String, Object> second = derivativesField(context).get();

      assertNotSame(first, second);
      assertEquals(singleton("attr1"), first.keySet());
      assertEquals(new HashSet<>(asList("attr1", "attr2")), second.keySet());
    }

    @Test
    void publishedDerivativesMapRejectsMutation() {
      AppSecRequestContext context = new AppSecRequestContext();
      context.reportDerivatives(singletonMap("test_attr", literalValue("test_value")));
      Map<String, Object> published = derivativesField(context).get();

      assertThrows(UnsupportedOperationException.class, published::clear);
    }

    @ParameterizedTest(name = "hasDerivativeKeyStartingWith: {0}")
    @MethodSource("com.datadog.appsec.gateway.AppSecRequestContextTest#derivativePrefixScenarios")
    void hasDerivativeKeyStartingWith(
        String scenario, Map<String, Object> derivatives, boolean expected) {
      AppSecRequestContext context = new AppSecRequestContext();
      context.reportDerivatives(derivatives);

      assertEquals(expected, context.hasDerivativeKeyStartingWith("_dd.appsec.s."));
    }

    @Test
    void aNullDerivativeKeyIsStoredButNeverMatches() {
      AppSecRequestContext context = new AppSecRequestContext();
      context.reportDerivatives(singletonMap(null, literalValue("x")));

      assertTrue(context.getDerivativeKeys().contains(null));
      assertFalse(context.hasDerivativeKeyStartingWith("_dd.appsec.s."));
    }
  }

  /** Declared on the outer class: {@code @MethodSource} factories must be static. */
  private static Stream<Arguments> derivativePrefixScenarios() {
    Map<String, Object> severalKeys = new HashMap<>();
    severalKeys.put("_dd.appsec.fp.http.header", literalValue("x"));
    severalKeys.put("_dd.appsec.s.req.body", literalValue("y"));
    return Stream.of(
        arguments("no derivatives reported", emptyMap(), false),
        arguments("matching key", singletonMap("_dd.appsec.s.req.body", literalValue("x")), true),
        arguments(
            "non-matching key",
            singletonMap("_dd.appsec.fp.http.header", literalValue("x")),
            false),
        arguments("one of several matches", severalKeys, true));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> requestHeadersField(AppSecRequestContext context) {
    return (Map<String, List<String>>) readField(context, "requestHeaders");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, List<String>> responseHeadersField(AppSecRequestContext context) {
    return (Map<String, List<String>>) readField(context, "responseHeaders");
  }

  @SuppressWarnings("unchecked")
  private static AtomicReference<Map<String, Object>> derivativesField(
      AppSecRequestContext context) {
    return (AtomicReference<Map<String, Object>>) readField(context, "derivatives");
  }

  /**
   * Reads {@link AppSecRequestContext} private state for assertions whose invariant is about which
   * instance the context still points at, rather than what its accessors report. The header getters
   * null-coalesce to {@code emptyMap()} and {@code getDerivativeKeys} exposes only a key view, so
   * neither can distinguish "released" from "empty".
   */
  private static Object readField(AppSecRequestContext context, String fieldName) {
    try {
      Field field = AppSecRequestContext.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(context);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Cannot read " + fieldName, e);
    }
  }
}
