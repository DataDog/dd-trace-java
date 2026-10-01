package datadog.trace.api.openfeature;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.ufc.v1.Allocation;
import datadog.trace.api.featureflag.ufc.v1.Flag;
import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import datadog.trace.api.featureflag.ufc.v1.Split;
import datadog.trace.api.featureflag.ufc.v1.ValueType;
import datadog.trace.api.featureflag.ufc.v1.Variant;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExposureHookTest {
  private static final String LOGGING_PROPERTY =
      "dd.feature.flags.exposures.datadog.logging.enabled";
  private final String previousProperty = System.getProperty(LOGGING_PROPERTY);
  private final OpenFeatureAPI api = OpenFeatureAPI.getInstance();
  private final List<ExposureEvent> datadog = new CopyOnWriteArrayList<>();
  private final FeatureFlaggingGateway.ExposureListener listener = datadog::add;

  @AfterEach
  void cleanup() {
    api.shutdown();
    FeatureFlaggingGateway.removeExposureListener(listener);
    FeatureFlaggingGateway.dispatch((ServerConfiguration) null);
    if (previousProperty == null) {
      System.clearProperty(LOGGING_PROPERTY);
    } else {
      System.setProperty(LOGGING_PROPERTY, previousProperty);
    }
  }

  @Test
  void datadogLoggingIsRegisteredByDefaultWithoutACustomerHook() throws Exception {
    System.clearProperty(LOGGING_PROPERTY);
    final Client client = client();
    assertEquals(true, client.getBooleanValue("flag", false, new MutableContext("user")));
    assertEquals(true, client.getBooleanValue("flag", false, new MutableContext("user")));
    assertEquals(1, datadog.size());
    assertEquals(Integer.valueOf(42), datadog.get(0).serial_id);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void destinationsShareAdvisoriesAndCustomersCanIgnoreTheCache(final boolean loggingEnabled)
      throws Exception {
    System.setProperty(LOGGING_PROPERTY, Boolean.toString(loggingEnabled));
    final Client client = client();
    final List<ExposureHook.Evaluation> evaluations = new ArrayList<>();
    final List<ExposureHook.Evaluation> allExposures = new ArrayList<>();
    client.addHooks(
        new ExposureHook(evaluations::add),
        new ExposureHook(
            e -> {
              if (e.isExposure()) {
                allExposures.add(e); // Deliberately ignore the cache advisory.
              }
            }));
    client.getBooleanValue("flag", false, new MutableContext("user"));
    client.getBooleanValue("flag", false, new MutableContext("user"));
    client.getBooleanValue("flag", false, new MutableContext("other"));
    client.getBooleanValue("rollout", false, new MutableContext("user"));
    client.getBooleanValue("disabled", false, new MutableContext("user"));
    client.getBooleanValue("missing", false, new MutableContext("user"));
    client.getStringValue("flag", "fallback", new MutableContext("user"));

    assertEquals(7, evaluations.size());
    assertEquals(3, allExposures.size());
    assertEquals(2, evaluations.stream().filter(ExposureHook.Evaluation::shouldSend).count());
    assertEquals(Boolean.FALSE, evaluations.get(0).getCacheHit());
    assertEquals(Boolean.TRUE, evaluations.get(1).getCacheHit());
    assertEquals(Boolean.FALSE, evaluations.get(2).getCacheHit());
    assertEquals("user", evaluations.get(0).getContext().getTargetingKey());
    assertEquals(
        Integer.valueOf(42),
        evaluations
            .get(0)
            .getDetails()
            .getFlagMetadata()
            .getInteger(DDEvaluator.METADATA_SPLIT_SERIAL_ID));
    for (int i = 3; i < evaluations.size(); i++) {
      assertFalse(evaluations.get(i).isExposure());
      assertNull(evaluations.get(i).getCacheHit());
    }
    assertEquals(ErrorCode.FLAG_NOT_FOUND, evaluations.get(5).getDetails().getErrorCode());
    assertEquals(ErrorCode.TYPE_MISMATCH, evaluations.get(6).getDetails().getErrorCode());
    assertEquals(loggingEnabled ? 2 : 0, datadog.size());
  }

  @Test
  void customerFailureDoesNotChangeTheResultOrOtherHooks() throws Exception {
    System.setProperty(LOGGING_PROPERTY, "true");
    final Client client = client();
    final List<ExposureHook.Evaluation> evaluations = new ArrayList<>();
    client.addHooks(
        new ExposureHook(evaluations::add),
        new ExposureHook(
            e -> {
              throw new IllegalStateException("customer destination unavailable");
            }));
    assertTrue(client.getBooleanValue("flag", false, new MutableContext("user")));
    assertTrue(client.getBooleanValue("flag", false, new MutableContext("user")));
    assertEquals(1, datadog.size());
    assertEquals(2, evaluations.size());
    assertTrue(evaluations.get(1).getCacheHit()); // Observation, not a delivery receipt.
  }

  @Test
  void lateRegistrationDoesNotReplayAssignments() throws Exception {
    System.setProperty(LOGGING_PROPERTY, "false");
    final Client client = client();
    client.getBooleanValue("flag", false, new MutableContext("user"));
    final List<ExposureHook.Evaluation> evaluations = new ArrayList<>();
    client.addHooks(new ExposureHook(evaluations::add));
    client.getBooleanValue("flag", false, new MutableContext("user"));
    assertTrue(evaluations.get(0).getCacheHit());
    assertFalse(evaluations.get(0).shouldSend());
    assertTrue(datadog.isEmpty());
  }

  @Test
  void concurrentEvaluationsHaveOneMissForBothDestinations() throws Exception {
    System.setProperty(LOGGING_PROPERTY, "true");
    final Client client = client();
    final List<ExposureHook.Evaluation> evaluations = new CopyOnWriteArrayList<>();
    client.addHooks(new ExposureHook(evaluations::add));
    final ExecutorService executor = Executors.newFixedThreadPool(8);
    final CountDownLatch start = new CountDownLatch(1);
    try {
      final List<Future<Boolean>> results = new ArrayList<>();
      for (int i = 0; i < 40; i++) {
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  return client.getBooleanValue("flag", false, new MutableContext("user"));
                }));
      }
      start.countDown();
      for (final Future<Boolean> result : results) {
        assertTrue(result.get(10, SECONDS));
      }
    } finally {
      executor.shutdownNow();
    }
    assertEquals(40, evaluations.size());
    assertEquals(1, evaluations.stream().filter(ExposureHook.Evaluation::shouldSend).count());
    assertEquals(1, datadog.size());
  }

  @Test
  void rejectedResultIsNotAnExposureButResolutionStillAdvancesTheAdvisoryCache() throws Exception {
    System.setProperty(LOGGING_PROPERTY, "true");
    final Client client = client();
    final List<ExposureHook.Evaluation> evaluations = new ArrayList<>();
    client.addHooks(
        new ExposureHook(evaluations::add),
        new Hook<Object>() {
          @Override
          public void after(
              final HookContext<Object> context,
              final FlagEvaluationDetails<Object> details,
              final Map<String, Object> hints) {
            throw new IllegalStateException("application rejected the result");
          }
        });
    assertFalse(client.getBooleanValue("flag", false, new MutableContext("user")));
    assertFalse(evaluations.get(0).isExposure());
    assertTrue(datadog.isEmpty());
    final FlagEvaluationDetails<Boolean> next =
        api.getClient().getBooleanDetails("flag", false, new MutableContext("user"));
    assertTrue(next.getValue());
    assertEquals(
        Boolean.TRUE, next.getFlagMetadata().getBoolean(ExposureHook.CACHE_HIT_METADATA_KEY));
    assertTrue(datadog.isEmpty());
  }

  private Client client() throws Exception {
    final DDEvaluator evaluator = new DDEvaluator(() -> {});
    final List<Flag> flags =
        List.of(
            flag("flag", true, true), flag("rollout", true, false), flag("disabled", false, true));
    evaluator.accept(
        new ServerConfiguration(
            "", "", false, null, flags.stream().collect(Collectors.toMap(f -> f.key, f -> f))));
    FeatureFlaggingGateway.addExposureListener(listener);
    final Provider provider =
        new Provider(new Provider.Options().initTimeout(1, SECONDS), evaluator, false);
    api.setProviderAndWait(provider);
    return api.getClient();
  }

  private static Flag flag(final String key, final boolean enabled, final boolean doLog) {
    final Split split = new Split(emptyList(), "on", emptyMap(), 42);
    final Allocation allocation =
        new Allocation("allocation", null, null, null, singletonList(split), doLog);
    return new Flag(
        key,
        enabled,
        ValueType.BOOLEAN,
        singletonMap("on", new Variant("on", true)),
        singletonList(allocation));
  }
}
