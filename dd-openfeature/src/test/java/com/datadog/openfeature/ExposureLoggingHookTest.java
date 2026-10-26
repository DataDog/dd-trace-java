package com.datadog.openfeature;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.ALLOCATION_KEY;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.DO_LOG;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_CACHE_HIT;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.SPLIT_SERIAL_ID;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.Provider.Options;
import com.datadog.openfeature.internal.bridge.v1.AgentBridge;
import com.datadog.openfeature.internal.config.TestSettings;
import com.datadog.openfeature.internal.exposure.ExposureEvent;
import com.datadog.openfeature.internal.ufc.Allocation;
import com.datadog.openfeature.internal.ufc.Flag;
import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import com.datadog.openfeature.internal.ufc.Split;
import com.datadog.openfeature.internal.ufc.ValueType;
import com.datadog.openfeature.internal.ufc.Variant;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.multiprovider.MultiProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExposureLoggingHookTest {

  private ExposureTestEvaluator evaluator;
  private List<ExposureEvent> dispatched;
  private Client client;

  @BeforeEach
  void setUp() throws Exception {
    this.evaluator = new ExposureTestEvaluator(configuration("on", Boolean.TRUE), true);
    this.dispatched = this.evaluator.sentToDatadog;
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(new Provider(new Options().initTimeout(10, SECONDS), this.evaluator));
    this.client = api.getClient();
  }

  @AfterEach
  void tearDown() {
    OpenFeatureAPI.getInstance().clearHooks();
    OpenFeatureAPI.getInstance().shutdown();
  }

  @Test
  void sendsExposuresWhenWrappedInAMultiProvider() throws Exception {
    final ExposureTestEvaluator wrapped =
        new ExposureTestEvaluator(configuration("on", Boolean.TRUE), true);
    OpenFeatureAPI.getInstance()
        .setProviderAndWait(
            new MultiProvider(
                singletonList(new Provider(new Options().initTimeout(10, SECONDS), wrapped))));

    evaluate("user-1");
    evaluate("user-1");

    assertEquals(1, wrapped.sentToDatadog.size());
    assertEquals("user-1", wrapped.sentToDatadog.get(0).subject.id);
  }

  @Test
  void sendsOneExposurePerSubject() {
    evaluate("user-1");
    evaluate("user-1");
    evaluate("user-2");

    assertEquals(2, this.dispatched.size());
    assertEquals("user-1", this.dispatched.get(0).subject.id);
    assertEquals("user-2", this.dispatched.get(1).subject.id);
    assertEquals("flag", this.dispatched.get(0).flag.key);
    assertEquals("allocation", this.dispatched.get(0).allocation.key);
    assertEquals("on", this.dispatched.get(0).variant.key);
    assertEquals(Integer.valueOf(7), this.dispatched.get(0).serial_id);
    assertNull(this.dispatched.get(0).features);
  }

  @Test
  void stampsWhetherTheSubjectWasAlreadyExposed() {
    assertEquals(Boolean.FALSE, cacheHit(evaluate("user-1")));
    assertEquals(Boolean.TRUE, cacheHit(evaluate("user-1")));
  }

  @Test
  void sendsAgainWhenTheVariantChanges() {
    evaluate("user-1");
    this.evaluator.accept(configuration("off", Boolean.TRUE));
    evaluate("user-1");

    assertEquals(2, this.dispatched.size());
    assertEquals("off", this.dispatched.get(1).variant.key);
  }

  @Test
  void aFailedEvaluationSendsNothingAndLeavesTheSubjectUnexposed() {
    final Hook<Integer> failingAfterHook =
        new Hook<Integer>() {
          @Override
          public void after(
              final HookContext<Integer> ctx,
              final FlagEvaluationDetails<Integer> details,
              final Map<String, Object> hints) {
            throw new IllegalStateException("application validation failed");
          }
        };
    OpenFeatureAPI.getInstance().addHooks(failingAfterHook);

    final FlagEvaluationDetails<Integer> failed = evaluate("user-1");

    assertEquals(ErrorCode.GENERAL, failed.getErrorCode());
    assertTrue(this.dispatched.isEmpty());
    assertEquals(0, this.evaluator.cache.size());

    OpenFeatureAPI.getInstance().clearHooks();
    evaluate("user-1");

    assertEquals(1, this.dispatched.size());
  }

  @Test
  void sendsNothingForAnAllocationThatDoesNotLogExposures() {
    this.evaluator.accept(configuration("on", Boolean.FALSE));

    final FlagEvaluationDetails<Integer> details = evaluate("user-1");

    assertTrue(this.dispatched.isEmpty());
    assertNull(cacheHit(details));
  }

  @Test
  void recordsButDoesNotSendWhenDatadogLoggingIsOff() {
    final ExposureTestEvaluator notSending =
        new ExposureTestEvaluator(configuration("on", Boolean.TRUE), false);
    final ExposureLoggingHook<Object> hook = new ExposureLoggingHook<>(() -> notSending);

    hook.finallyAfter(hookContext(), details("on", exposureMetadata(true, false, "a")), emptyMap());

    assertTrue(notSending.sentToDatadog.isEmpty());
    assertEquals(1, notSending.cache.size());
  }

  @Test
  void sendsNothingWithoutTheDetailsAnExposureNeeds() {
    final ExposureTestEvaluator target =
        new ExposureTestEvaluator(configuration("on", Boolean.TRUE), true);
    final ExposureLoggingHook<Object> hook = new ExposureLoggingHook<>(() -> target);
    final HookContext<Object> withContext = hookContext();

    hook.finallyAfter(withContext, null, emptyMap());
    hook.finallyAfter(withContext, details("variant", null), emptyMap());
    hook.finallyAfter(
        withContext, details("variant", exposureMetadata(false, false, "a")), emptyMap());
    hook.finallyAfter(
        withContext, details("variant", exposureMetadata(true, true, "a")), emptyMap());
    hook.finallyAfter(
        withContext, details("variant", exposureMetadata(true, false, null)), emptyMap());
    hook.finallyAfter(withContext, details(null, exposureMetadata(true, false, "a")), emptyMap());
    hook.finallyAfter(null, details("variant", exposureMetadata(true, false, "a")), emptyMap());
    hook.finallyAfter(
        withContext,
        FlagEvaluationDetails.<Object>builder()
            .flagKey("flag")
            .value(1)
            .variant("variant")
            .errorCode(ErrorCode.GENERAL)
            .flagMetadata(exposureMetadata(true, false, "a"))
            .build(),
        emptyMap());

    assertTrue(target.sentToDatadog.isEmpty());
    assertEquals(0, target.cache.size());

    hook.finallyAfter(
        withContext, details("variant", exposureMetadata(true, false, "a")), emptyMap());

    assertEquals(1, target.sentToDatadog.size());
    assertEquals(1, target.cache.size());
  }

  @Test
  void sendsNothingWithoutAnInitializedDatadogEvaluator() {
    final ExposureLoggingHook<Object> withoutEvaluator = new ExposureLoggingHook<>(() -> null);
    final DDEvaluator uninitialized =
        new DDEvaluator(() -> {}, AgentBridge.NONE, TestSettings.of());
    final ExposureLoggingHook<Object> withoutRuntime =
        new ExposureLoggingHook<>(() -> uninitialized);

    assertDoesNotThrow(
        () ->
            withoutEvaluator.finallyAfter(
                hookContext(), details("on", exposureMetadata(true, false, "a")), emptyMap()));
    assertDoesNotThrow(
        () ->
            withoutRuntime.finallyAfter(
                hookContext(), details("on", exposureMetadata(true, false, "a")), emptyMap()));
  }

  @Test
  void readsTheSerialIdFromTheMetadata() {
    final ExposureTestEvaluator target =
        new ExposureTestEvaluator(configuration("on", Boolean.TRUE), true);
    final ExposureLoggingHook<Object> hook = new ExposureLoggingHook<>(() -> target);
    final ImmutableMetadata metadata =
        ImmutableMetadata.builder()
            .addBoolean(DO_LOG, true)
            .addBoolean(EXPOSURE_CACHE_HIT, false)
            .addString(ALLOCATION_KEY, "a")
            .addInteger(SPLIT_SERIAL_ID, 42)
            .build();

    hook.finallyAfter(hookContext(), details("on", metadata), emptyMap());

    assertEquals(Integer.valueOf(42), target.sentToDatadog.get(0).serial_id);
    assertTrue(target.cache.contains("flag", "user-1", "a", "on", 42));
  }

  @Test
  void containsFailuresOfTheEvaluator() {
    final ExposureTestEvaluator failing =
        new ExposureTestEvaluator(configuration("on", Boolean.TRUE), true) {
          @Override
          void recordExposure(final ExposureEvent event) {
            throw new IllegalStateException("queue failure");
          }
        };
    final ExposureLoggingHook<Object> hook = new ExposureLoggingHook<>(() -> failing);
    final HookContext<Object> context = hookContext();

    assertDoesNotThrow(
        () ->
            hook.finallyAfter(
                context, details("on", exposureMetadata(true, false, "a")), emptyMap()));
    assertDoesNotThrow(
        () ->
            hook.finallyAfter(
                context, details("off", exposureMetadata(true, false, "a")), emptyMap()));
  }

  private static Object cacheHit(final FlagEvaluationDetails<Integer> details) {
    return details.getFlagMetadata().asUnmodifiableMap().get(EXPOSURE_CACHE_HIT);
  }

  private static HookContext<Object> hookContext() {
    return HookContext.<Object>builder()
        .flagKey("flag")
        .type(FlagValueType.INTEGER)
        .defaultValue(0)
        .ctx(new MutableContext("user-1"))
        .build();
  }

  private static FlagEvaluationDetails<Object> details(
      final String variant, final ImmutableMetadata metadata) {
    final FlagEvaluationDetails.FlagEvaluationDetailsBuilder<Object> builder =
        FlagEvaluationDetails.<Object>builder().flagKey("flag").value(1).variant(variant);
    if (metadata != null) {
      builder.flagMetadata(metadata);
    }
    return builder.build();
  }

  private static ImmutableMetadata exposureMetadata(
      final boolean doLog, final boolean cacheHit, final String allocationKey) {
    final ImmutableMetadata.ImmutableMetadataBuilder builder =
        ImmutableMetadata.builder()
            .addBoolean(DO_LOG, doLog)
            .addBoolean(EXPOSURE_CACHE_HIT, cacheHit);
    if (allocationKey != null) {
      builder.addString(ALLOCATION_KEY, allocationKey);
    }
    return builder.build();
  }

  private FlagEvaluationDetails<Integer> evaluate(final String targetingKey) {
    return this.client.getIntegerDetails("flag", 0, new MutableContext(targetingKey));
  }

  private static ServerConfiguration configuration(final String variantKey, final Boolean doLog) {
    final Map<String, Variant> variations = new HashMap<>();
    variations.put("on", new Variant("on", 1));
    variations.put("off", new Variant("off", 0));
    final Split split = new Split(emptyList(), variantKey, emptyMap(), 7);
    final Allocation allocation =
        new Allocation("allocation", null, null, null, singletonList(split), doLog);
    final Map<String, Flag> flags = new HashMap<>();
    flags.put(
        "flag", new Flag("flag", true, ValueType.INTEGER, variations, singletonList(allocation)));
    return new ServerConfiguration("", "", false, null, flags);
  }
}
