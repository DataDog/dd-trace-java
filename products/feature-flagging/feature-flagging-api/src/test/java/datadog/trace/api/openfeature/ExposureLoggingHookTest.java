package datadog.trace.api.openfeature;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import datadog.trace.api.openfeature.Provider.Options;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExposureLoggingHookTest {

  private final List<ExposureEvent> dispatched = new ArrayList<>();
  private final FeatureFlaggingGateway.ExposureListener listener = dispatched::add;
  private Client client;

  @BeforeEach
  void setUp() throws Exception {
    ExposureDeduplicationCache.INSTANCE.clear();
    FeatureFlaggingGateway.dispatch(configuration("on", Boolean.TRUE));
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(
        new Provider(
            new Options().initTimeout(10, SECONDS), new DDEvaluator(mock(Runnable.class))));
    client = api.getClient();
    FeatureFlaggingGateway.addExposureListener(listener);
  }

  @AfterEach
  void tearDown() {
    FeatureFlaggingGateway.removeExposureListener(listener);
    OpenFeatureAPI.getInstance().clearHooks();
    OpenFeatureAPI.getInstance().shutdown();
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    ExposureDeduplicationCache.INSTANCE.clear();
  }

  @Test
  void sendsOneExposurePerSubject() {
    evaluate("user-1");
    evaluate("user-1");
    evaluate("user-2");

    assertEquals(2, dispatched.size());
    assertEquals("user-1", dispatched.get(0).subject.id);
    assertEquals("user-2", dispatched.get(1).subject.id);
    assertEquals("flag", dispatched.get(0).flag.key);
    assertEquals("allocation", dispatched.get(0).allocation.key);
    assertEquals("on", dispatched.get(0).variant.key);
    assertEquals(Integer.valueOf(7), dispatched.get(0).serial_id);
  }

  @Test
  void stampsWhetherTheSubjectWasAlreadyExposed() {
    assertEquals(
        Boolean.FALSE,
        evaluate("user-1").getFlagMetadata().getBoolean(DDEvaluator.METADATA_EXPOSURE_CACHE_HIT));
    assertEquals(
        Boolean.TRUE,
        evaluate("user-1").getFlagMetadata().getBoolean(DDEvaluator.METADATA_EXPOSURE_CACHE_HIT));
  }

  @Test
  void sendsAgainWhenTheVariantChanges() {
    evaluate("user-1");
    FeatureFlaggingGateway.dispatch(configuration("off", Boolean.TRUE));
    evaluate("user-1");

    assertEquals(2, dispatched.size());
    assertEquals("off", dispatched.get(1).variant.key);
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
    assertTrue(dispatched.isEmpty());
    assertEquals(0, ExposureDeduplicationCache.INSTANCE.size());

    OpenFeatureAPI.getInstance().clearHooks();
    evaluate("user-1");

    assertEquals(1, dispatched.size());
  }

  @Test
  void sendsNothingForAnAllocationThatDoesNotLogExposures() {
    FeatureFlaggingGateway.dispatch(configuration("on", Boolean.FALSE));

    final FlagEvaluationDetails<Integer> details = evaluate("user-1");

    assertTrue(dispatched.isEmpty());
    assertNull(details.getFlagMetadata().getBoolean(DDEvaluator.METADATA_EXPOSURE_CACHE_HIT));
  }

  @Test
  void cacheEvictsTheLeastRecentlyUsedSubject() {
    final ExposureDeduplicationCache cache = new ExposureDeduplicationCache(2);
    cache.record("flag", "user-1", "allocation", "on", 7);
    cache.record("flag", "user-2", "allocation", "on", 7);
    assertTrue(cache.contains("flag", "user-1", "allocation", "on", 7));
    cache.record("flag", "user-3", "allocation", "on", 7);

    assertTrue(cache.contains("flag", "user-1", "allocation", "on", 7));
    assertFalse(cache.contains("flag", "user-2", "allocation", "on", 7));
    assertTrue(cache.contains("flag", "user-3", "allocation", "on", 7));
    assertFalse(cache.contains("flag", "user-1", "allocation", "on", 8));
  }

  private FlagEvaluationDetails<Integer> evaluate(final String targetingKey) {
    return client.getIntegerDetails("flag", 0, new MutableContext(targetingKey));
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
