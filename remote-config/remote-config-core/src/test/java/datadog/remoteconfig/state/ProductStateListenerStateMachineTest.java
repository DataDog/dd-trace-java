package datadog.remoteconfig.state;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.tuf.RemoteConfigResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductStateListenerStateMachineTest {

  private static final String CONFIG_PATH = "org/ASM_DATA/config1/foo";
  private static final ParsedConfigKey CONFIG_KEY = ParsedConfigKey.parse(CONFIG_PATH);

  private enum FailurePhase {
    ACCEPT,
    REMOVE,
    COMMIT
  }

  @Test
  void generatedListenerFailuresRemainRetryableForHealthyListeners() {
    Map<FailurePhase, Counterexample> distinctFindings = new EnumMap<>(FailurePhase.class);

    for (FailurePhase failurePhase : FailurePhase.values()) {
      Counterexample counterexample = execute(failurePhase);
      if (counterexample != null) {
        distinctFindings.put(failurePhase, counterexample);
      }
    }

    assertTrue(
        distinctFindings.isEmpty(),
        () ->
            "listener state machine found fan-out failures that replay cannot repair: "
                + distinctFindings.values());
  }

  private static Counterexample execute(FailurePhase failurePhase) {
    PollingRateHinter hinter = mock(PollingRateHinter.class);
    ProductState productState = new ProductState(Product.ASM_DATA);
    productState.addProductListener(new FailingListener(failurePhase));
    RecordingListener healthyListener = new RecordingListener();
    productState.addProductListener(healthyListener);

    RemoteConfigResponse present = responseWithConfig();
    List<String> expected;
    boolean replayChanged;
    if (failurePhase == FailurePhase.REMOVE) {
      productState.apply(present, singletonList(CONFIG_KEY), hinter);
      healthyListener.operations.clear();

      RemoteConfigResponse absent = responseWithoutConfig();
      productState.apply(absent, emptyList(), hinter);
      replayChanged = productState.apply(absent, emptyList(), hinter);
      expected = Arrays.asList("remove", "commit");
    } else {
      productState.apply(present, singletonList(CONFIG_KEY), hinter);
      replayChanged = productState.apply(present, singletonList(CONFIG_KEY), hinter);
      expected = Arrays.asList("accept", "commit");
    }

    if (!expected.equals(healthyListener.operations)) {
      return new Counterexample(failurePhase, expected, healthyListener.operations, replayChanged);
    }
    return null;
  }

  private static RemoteConfigResponse responseWithConfig() {
    RemoteConfigResponse response = mock(RemoteConfigResponse.class);
    RemoteConfigResponse.Targets.ConfigTarget target =
        new RemoteConfigResponse.Targets.ConfigTarget();
    target.hashes = singletonMap("sha256", "hash1");
    target.length = 8;
    RemoteConfigResponse.Targets.ConfigTarget.ConfigTargetCustom custom =
        new RemoteConfigResponse.Targets.ConfigTarget.ConfigTargetCustom();
    custom.version = 1;
    target.custom = custom;
    when(response.getTarget(CONFIG_PATH)).thenReturn(target);
    when(response.getFileContents(CONFIG_PATH)).thenReturn("content1".getBytes(UTF_8));
    return response;
  }

  private static RemoteConfigResponse responseWithoutConfig() {
    RemoteConfigResponse response = mock(RemoteConfigResponse.class);
    when(response.getTarget(any())).thenReturn(null);
    return response;
  }

  private static final class FailingListener implements ProductListener {
    private final FailurePhase failurePhase;

    private FailingListener(FailurePhase failurePhase) {
      this.failurePhase = failurePhase;
    }

    @Override
    public void accept(ConfigKey configKey, byte[] content, PollingRateHinter hinter) {
      failAt(FailurePhase.ACCEPT);
    }

    @Override
    public void remove(ConfigKey configKey, PollingRateHinter hinter) {
      failAt(FailurePhase.REMOVE);
    }

    @Override
    public void commit(PollingRateHinter hinter) {
      failAt(FailurePhase.COMMIT);
    }

    private void failAt(FailurePhase currentPhase) {
      if (failurePhase == currentPhase) {
        throw new IllegalStateException("injected " + currentPhase + " failure");
      }
    }
  }

  private static final class RecordingListener implements ProductListener {
    private final List<String> operations = new ArrayList<>();

    @Override
    public void accept(ConfigKey configKey, byte[] content, PollingRateHinter hinter) {
      operations.add("accept");
    }

    @Override
    public void remove(ConfigKey configKey, PollingRateHinter hinter) {
      operations.add("remove");
    }

    @Override
    public void commit(PollingRateHinter hinter) {
      operations.add("commit");
    }
  }

  private static final class Counterexample {
    private final FailurePhase failurePhase;
    private final List<String> expected;
    private final List<String> observed;
    private final boolean replayChanged;

    private Counterexample(
        FailurePhase failurePhase,
        List<String> expected,
        List<String> observed,
        boolean replayChanged) {
      this.failurePhase = failurePhase;
      this.expected = new ArrayList<>(expected);
      this.observed = new ArrayList<>(observed);
      this.replayChanged = replayChanged;
    }

    @Override
    public String toString() {
      return failurePhase
          + ": expected="
          + expected
          + ", observed="
          + observed
          + ", replayChanged="
          + replayChanged;
    }
  }
}
