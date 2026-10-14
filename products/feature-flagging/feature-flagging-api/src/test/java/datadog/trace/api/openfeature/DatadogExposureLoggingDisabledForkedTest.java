package datadog.trace.api.openfeature;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Turns off Datadog exposure logging through the real setting. The gate is read once, when
 * ExposureLoggingHook loads, so the property is set before that and the forked task gives this
 * class its own JVM.
 */
class DatadogExposureLoggingDisabledForkedTest {

  static {
    System.setProperty("dd.feature.flags.exposures.datadog.logging.enabled", "false");
  }

  private final List<ExposureEvent> dispatched = new ArrayList<>();
  private final FeatureFlaggingGateway.ExposureListener listener = dispatched::add;

  @AfterEach
  void tearDown() {
    FeatureFlaggingGateway.removeExposureListener(listener);
    OpenFeatureAPI.getInstance().clearHooks();
    OpenFeatureAPI.getInstance().shutdown();
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    ExposureDeduplicationCache.INSTANCE.clear();
  }

  @Test
  void sendsNoExposuresToDatadogWhileExposureHooksKeepTheirDecisions() throws Exception {
    final List<ExposureHook.Evaluation> evaluations = new ArrayList<>();
    FeatureFlaggingGateway.dispatch(configuration());
    final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
    api.setProviderAndWait(
        new Provider(
            new Options().initTimeout(10, SECONDS), new DDEvaluator(mock(Runnable.class))));
    api.addHooks(new ExposureHook(evaluations::add));
    FeatureFlaggingGateway.addExposureListener(listener);
    final Client client = api.getClient();

    client.getIntegerDetails("flag", 0, new MutableContext("user-1"));
    client.getIntegerDetails("flag", 0, new MutableContext("user-1"));

    assertTrue(dispatched.isEmpty(), "nothing is sent to Datadog");
    assertEquals(2, evaluations.size());
    assertTrue(evaluations.get(0).shouldSend());
    assertFalse(evaluations.get(1).shouldSend(), "the cache still records the first exposure");
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
