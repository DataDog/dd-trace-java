package com.datadog.openfeature;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.openfeature.Provider.Options;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import com.datadog.openfeature.testlog.CapturingServiceProvider;
import com.datadog.openfeature.testlog.CapturingServiceProvider.Event;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.Value;
import java.time.Duration;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

class DebugLoggingTest {
  private static final String TRACE_DEBUG = "dd.trace.debug";
  private static final String AGENTLESS_BASE_URL =
      "dd.feature.flags.configuration.source.agentless.base.url";

  private static final String SDK_LOGGERS = "com.datadog.openfeature";

  @BeforeEach
  void captureLogs() {
    CapturingServiceProvider.reset();
    CapturingServiceProvider.enable(SDK_LOGGERS, Level.DEBUG);
  }

  @AfterEach
  void restore() {
    System.clearProperty(TRACE_DEBUG);
    System.clearProperty(AGENTLESS_BASE_URL);
    OpenFeatureAPI.getInstance().clearHooks();
    OpenFeatureAPI.getInstance().shutdown();
    CapturingServiceProvider.reset();
  }

  @Test
  void logsTheRawEvaluationWhenTraceDebugIsOn() throws Exception {
    System.setProperty(TRACE_DEBUG, "true");
    final Client client = register();

    client.getIntegerValue("flag", 5, new MutableContext("user-1").add("plan", "pro"));

    final String message = singleMessage("Feature Flagging evaluation:");
    assertTrue(message.contains("flag=flag"), message);
    assertTrue(message.contains("targetingKey=user-1"), message);
    assertTrue(message.contains("plan=pro"), message);
    assertTrue(message.contains("defaultValue=5"), message);
    assertTrue(message.contains("value=1"), message);
    assertTrue(message.contains("variant=on"), message);
    assertTrue(message.contains("reason=STATIC"), message);
    assertEquals(
        Level.DEBUG,
        CapturingServiceProvider.events().stream()
            .filter(e -> e.message.equals(message))
            .findFirst()
            .get()
            .level);
  }

  @Test
  void logsTheErrorOfAnEvaluation() throws Exception {
    System.setProperty(TRACE_DEBUG, "true");
    final Client client = register();

    client.getIntegerValue("missing", 5, new MutableContext("user-1"));

    final String message = singleMessage("Feature Flagging evaluation:");
    assertTrue(message.contains("flag=missing"), message);
    assertTrue(message.contains("errorCode=FLAG_NOT_FOUND"), message);
  }

  @Test
  void doesNotLogRawEvaluationsWithoutTraceDebug() throws Exception {
    // Explicit, so that a DD_TRACE_DEBUG in the environment of the test JVM does not matter.
    System.setProperty(TRACE_DEBUG, "false");
    final Client client = register();

    client.getIntegerValue("flag", 5, new MutableContext("user-1"));

    assertTrue(messages("Feature Flagging evaluation:").isEmpty());
  }

  @Test
  void warnsWhenTraceDebugIsOnButTheLoggerDoesNotAllowDebug() throws Exception {
    System.setProperty(TRACE_DEBUG, "true");
    CapturingServiceProvider.enable(DebugLoggingHook.class.getName(), Level.INFO);
    final Client client = register();

    client.getIntegerValue("flag", 5, new MutableContext("user-1"));

    assertTrue(messages("Feature Flagging evaluation:").isEmpty());
    final List<Event> warnings =
        CapturingServiceProvider.events().stream()
            .filter(e -> e.level == Level.WARN)
            .filter(e -> e.message.contains("DD_TRACE_DEBUG is enabled"))
            .collect(Collectors.toList());
    assertEquals(1, warnings.size());
  }

  @Test
  void stopsLoggingRawEvaluationsWhenTheLoggerIsLoweredAfterRegistration() throws Exception {
    System.setProperty(TRACE_DEBUG, "true");
    final Client client = register();
    client.getIntegerValue("flag", 5, new MutableContext("user-1"));
    assertEquals(1, messages("Feature Flagging evaluation:").size());

    CapturingServiceProvider.enable(DebugLoggingHook.class.getName(), Level.INFO);
    client.getIntegerValue("flag", 5, new MutableContext("user-2"));

    assertEquals(1, messages("Feature Flagging evaluation:").size());
  }

  @Test
  void announcesThatRawEvaluationsAreLoggedWhenTheHookIsRegistered() throws Exception {
    System.setProperty(TRACE_DEBUG, "true");
    register();

    final List<Event> announcements =
        CapturingServiceProvider.events().stream()
            .filter(e -> e.message.contains("personal data"))
            .collect(Collectors.toList());
    assertEquals(1, announcements.size(), announcements.toString());
    assertEquals(Level.INFO, announcements.get(0).level);
  }

  @Test
  void announcesThroughTheLoggerOfTheHookAlone() throws Exception {
    System.setProperty(TRACE_DEBUG, "true");
    // Only the category named by the provider warning is enabled: the provider logger is not.
    CapturingServiceProvider.enable(SDK_LOGGERS, Level.WARN);
    CapturingServiceProvider.enable(DebugLoggingHook.class.getName(), Level.DEBUG);
    register();

    final List<Event> announcements =
        CapturingServiceProvider.events().stream()
            .filter(e -> e.message.contains("personal data"))
            .collect(Collectors.toList());
    assertEquals(1, announcements.size(), announcements.toString());
    assertEquals(DebugLoggingHook.class.getName(), announcements.get(0).logger);
    assertEquals(Level.INFO, announcements.get(0).level);
  }

  @Test
  void doesNotAnnounceRawLoggingWithoutTraceDebug() throws Exception {
    System.setProperty(TRACE_DEBUG, "false");
    register();

    assertTrue(messages("DD_TRACE_DEBUG is enabled").isEmpty());
  }

  @Test
  void containsAFailureToFormatTheEvaluation() {
    final EvaluationContext failing = mock(EvaluationContext.class);
    when(failing.keySet()).thenThrow(new IllegalStateException("cannot render"));
    final HookContext<Object> hookContext =
        HookContext.<Object>builder()
            .flagKey("flag")
            .type(FlagValueType.INTEGER)
            .defaultValue(0)
            .ctx(failing)
            .build();
    final FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder().flagKey("flag").value(1).build();

    assertDoesNotThrow(
        () -> new DebugLoggingHook<>().finallyAfter(hookContext, details, emptyMap()));

    assertEquals(
        1,
        messages("Feature Flagging evaluation diagnostic unavailable: java.lang.IllegalState")
            .size());
  }

  @Test
  void doesNotExplainExposureSuppressionsAtDebug() throws Exception {
    final Client client = register();

    client.getIntegerValue("missing", 5, new MutableContext("user-1"));
    client.getIntegerValue("flag", 5, new MutableContext("user-1"));
    client.getIntegerValue("flag", 5, new MutableContext("user-1"));

    assertTrue(messages("Feature Flagging exposure suppressed").isEmpty());
  }

  @Test
  void capsTheLengthOfALoggedContextValue() throws Exception {
    System.setProperty(TRACE_DEBUG, "true");
    final Client client = register();
    final String blob = "x".repeat(5_000);

    client.getIntegerValue("flag", 5, new MutableContext("user-1").add("blob", blob));

    final String message = singleMessage("Feature Flagging evaluation:");
    assertTrue(message.contains("...(truncated)"), message);
    assertFalse(message.contains(blob), message);
    assertTrue(message.length() < 3_000, "Logged " + message.length() + " characters");
  }

  @Test
  void rendersNestedValuesAsTheyAre() {
    final Value nested =
        new Value(
            Arrays.asList(
                new Value("a"), new Value(new MutableStructure().add("k", "v")), new Value(2)));

    final String message = hookMessage(hookContext(new MutableContext("u"), nested), details());

    assertTrue(message.contains("defaultValue=[a, {k=v}, 2]"), message);
    assertTrue(message.contains("context={targetingKey=u}"), message);
  }

  @Test
  void doesNotFollowAValueThatContainsItself() {
    final List<Value> list = new ArrayList<>();
    final Value cyclic = new Value(list);
    list.add(cyclic);
    final Map<String, Value> attributes = new HashMap<>();
    attributes.put("loop", cyclic);

    final String message =
        hookMessage(hookContext(new MutableContext("u", attributes), new Value(0)), details());

    assertTrue(message.contains("loop=[(cycle)]"), message);
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void doesNotFollowAListThatContainsNullAndItself() {
    final List<Object> list = new ArrayList<>();
    final Value cyclic = new Value((List) list);
    list.add(null);
    list.add(cyclic);

    final String message = hookMessage(hookContext(new MutableContext("u"), cyclic), details());

    assertTrue(message.contains("defaultValue=[null, (cycle)]"), message);
  }

  @Test
  void stopsWalkingAListOnceTheBudgetIsSpent() {
    final int[] reads = {0};
    final List<String> wide =
        new AbstractList<String>() {
          @Override
          public String get(final int index) {
            reads[0]++;
            return "element-" + index;
          }

          @Override
          public int size() {
            return 1_000_000;
          }
        };

    final String message = hookMessage(hookContext(new MutableContext("u"), wide), details());

    assertTrue(message.contains("...(truncated)"), message);
    assertTrue(reads[0] < 1_000, "Read " + reads[0] + " elements");
  }

  @Test
  void doesNotScanAWrappedListBeforeRenderingIt() {
    final int[] reads = {0};
    final List<Value> wide =
        new AbstractList<Value>() {
          @Override
          public Value get(final int index) {
            reads[0]++;
            return new Value("element-" + index);
          }

          @Override
          public int size() {
            return 1_000_000;
          }
        };

    final String message =
        hookMessage(hookContext(new MutableContext("u"), new Value(wide)), details());

    assertTrue(message.contains("...(truncated)"), message);
    assertTrue(reads[0] < 1_000, "Read " + reads[0] + " elements");
  }

  @Test
  void returnsFromAnInfiniteIterable() {
    final Iterable<String> infinite =
        () ->
            new Iterator<String>() {
              @Override
              public boolean hasNext() {
                return true;
              }

              @Override
              public String next() {
                return "x";
              }
            };

    final String message =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10),
            () -> hookMessage(hookContext(new MutableContext("u"), infinite), details()));

    assertTrue(message.contains("...(truncated)"), message);
  }

  @Test
  void doesNotRenderAMapKeyOutsideTheGuards() {
    final List<Object> first = new ArrayList<>();
    final List<Object> second = new ArrayList<>();
    first.add(second);
    second.add(first);
    final Map<Object, Object> map = new IdentityHashMap<>();
    map.put(first, "v");

    final String message = hookMessage(hookContext(new MutableContext("u"), map), details());

    assertTrue(message.contains("defaultValue={[[(cycle)]]=v}"), message);
  }

  @Test
  void rendersANullStructureKey() {
    final Value structure = new Value(new MutableStructure().add((String) null, "v"));

    final String message = hookMessage(hookContext(new MutableContext("u"), structure), details());

    assertTrue(message.contains("defaultValue={null=v}"), message);
  }

  @Test
  void doesNotOverflowTheStackOnAVeryDeepValue() {
    Value deep = new Value("leaf");
    for (int i = 0; i < 5_000; i++) {
      deep = new Value(Collections.singletonList(deep));
    }
    final Map<String, Value> attributes = new HashMap<>();
    attributes.put("deep", deep);

    final String message =
        hookMessage(hookContext(new MutableContext("u", attributes), new Value(0)), details());

    assertTrue(message.contains("deep=[[[[[[[[...]]]]]]]]"), message);
    assertFalse(message.contains("leaf"), message);
    assertTrue(message.length() < 2_000, "Logged " + message.length() + " characters");
  }

  @Test
  void rendersAWideContextWithinTheBudget() {
    final Map<String, Value> attributes = new HashMap<>();
    for (int i = 0; i < 20_000; i++) {
      attributes.put("attribute-" + i, new Value("value-" + i));
    }

    final String message =
        hookMessage(hookContext(new MutableContext("u", attributes), new Value(0)), details());

    assertTrue(message.contains("...(truncated)"), message);
    assertTrue(message.length() < 2_000, "Logged " + message.length() + " characters");
  }

  @Test
  void capsTheVariantAndTheReason() {
    final FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder()
            .flagKey("flag")
            .value(1)
            .variant("v".repeat(5_000))
            .reason("r".repeat(5_000))
            .build();

    final String message = hookMessage(hookContext(new MutableContext("u"), new Value(0)), details);

    assertTrue(
        message.contains("variant=" + "v".repeat(1_024) + "...(truncated, 5000 characters), "),
        message);
    assertTrue(
        message.contains("reason=" + "r".repeat(1_024) + "...(truncated, 5000 characters), "),
        message);
  }

  @Test
  void capsTheFlagKey() throws Exception {
    System.setProperty(TRACE_DEBUG, "true");
    final Client client = register();

    client.getIntegerValue("k".repeat(5_000), 5, new MutableContext("user-1"));

    final String message = singleMessage("Feature Flagging evaluation:");
    assertTrue(
        message.startsWith(
            "Feature Flagging evaluation: flag="
                + "k".repeat(1_024)
                + "...(truncated, 5000 characters), context="),
        message.substring(0, Math.min(message.length(), 1_200)));
  }

  @Test
  void doesNotCutASurrogatePairAtTheLimit() throws Exception {
    System.setProperty(TRACE_DEBUG, "true");
    final Client client = register();
    // The emoji is a surrogate pair whose first half is the last character below the limit.
    final String key = "k".repeat(1_023) + "\uD83D\uDE00" + "k".repeat(100);

    client.getIntegerValue(key, 5, new MutableContext("user-1"));

    final String message = singleMessage("Feature Flagging evaluation:");
    assertTrue(
        message.startsWith(
            "Feature Flagging evaluation: flag="
                + "k".repeat(1_023)
                + "...(truncated, 1125 characters), context="),
        message.substring(0, Math.min(message.length(), 1_200)));
    assertFalse(message.contains("\uD83D"), "A lone surrogate was logged");
  }

  @Test
  void doesNotLogTheCredentialsOfAnInvalidEndpoint() {
    System.setProperty(AGENTLESS_BASE_URL, "ftp://user:pw-secret@example.com/ufc?token=tok-secret");

    // The provider cannot initialize: the endpoint is invalid.
    assertThrows(
        Exception.class,
        () ->
            OpenFeatureAPI.getInstance()
                .setProviderAndWait(new Provider(new Options().initTimeout(1, SECONDS))));

    assertEquals(1, messages("Feature Flagging provider initialization failed: java.lang.").size());
    for (final Event event : CapturingServiceProvider.events()) {
      assertFalse(event.message.contains("pw-secret"), event.toString());
      assertFalse(event.message.contains("tok-secret"), event.toString());
    }
  }

  @Test
  void logsTheErrorMessageTheApplicationObserves() throws Exception {
    System.setProperty(TRACE_DEBUG, "true");
    final Client client = register();

    // The configuration does not observe full evaluation data, so the provider drops the message.
    client.getStringValue("flag", "default", new MutableContext("user-1"));

    final String message = singleMessage("Feature Flagging evaluation:");
    assertTrue(message.contains("errorCode=TYPE_MISMATCH"), message);
    assertTrue(message.contains("errorMessage=null"), message);
  }

  @Test
  void logsTheProviderLifecycle() throws Exception {
    register();
    OpenFeatureAPI.getInstance().shutdown();

    assertEquals(1, messages("Feature Flagging provider initializing").size());
    assertFalse(messages("Feature Flagging provider ready").isEmpty());
    assertFalse(messages("Feature Flagging provider shutting down").isEmpty());
  }

  @Test
  void explainsWhyAnExposureIsSuppressed() throws Exception {
    CapturingServiceProvider.enable(SDK_LOGGERS, Level.TRACE);
    final Client client = register();

    client.getIntegerValue("missing", 5, new MutableContext("user-1"));
    client.getIntegerValue("flag", 5, new MutableContext("user-1"));
    client.getIntegerValue("flag", 5, new MutableContext("user-1"));

    assertEquals(
        1,
        messages("Feature Flagging exposure suppressed: evaluation failed or details unavailable")
            .size());
    assertEquals(
        1, messages("Feature Flagging exposure suppressed: exposure already recorded").size());
  }

  private static HookContext<Object> hookContext(
      final EvaluationContext context, final Object defaultValue) {
    return HookContext.<Object>builder()
        .flagKey("flag")
        .type(FlagValueType.OBJECT)
        .defaultValue(defaultValue)
        .ctx(context)
        .build();
  }

  private static FlagEvaluationDetails<Object> details() {
    return FlagEvaluationDetails.<Object>builder().flagKey("flag").value(1).build();
  }

  private String hookMessage(
      final HookContext<Object> hookContext, final FlagEvaluationDetails<Object> details) {
    new DebugLoggingHook<>().finallyAfter(hookContext, details, emptyMap());
    return singleMessage("Feature Flagging evaluation:");
  }

  private Client register() throws Exception {
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(
        new Provider(
            new Options().initTimeout(10, SECONDS),
            new ExposureTestEvaluator(configuration(), true)));
    return api.getClient();
  }

  private String singleMessage(final String prefix) {
    final List<String> messages = messages(prefix);
    assertEquals(1, messages.size(), messages.toString());
    return messages.get(0);
  }

  private List<String> messages(final String prefix) {
    return CapturingServiceProvider.events().stream()
        .map(e -> e.message)
        .filter(message -> message.startsWith(prefix))
        .collect(Collectors.toList());
  }

  private static ServerConfiguration configuration() {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    final Split split = new Split(emptyList(), "on", emptyMap(), 7);
    final Allocation allocation =
        new Allocation("allocation", null, null, null, singletonList(split), Boolean.TRUE);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "flag", new Flag("flag", true, ValueType.INTEGER, variations, singletonList(allocation)));
    return new ServerConfiguration("", "", false, null, flags);
  }
}
