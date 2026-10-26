package datadog.trace.api.openfeature;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.FlagValueType;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.ImmutableContext;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks the deprecated exposure hook alias delegates to the SDK hook. */
@SuppressWarnings("deprecation")
class ExposureHookTest {
  @Test
  void aliasReportsTheExposureDecision() {
    final List<ExposureHook.Evaluation> evaluations = new ArrayList<>();
    final ExposureHook hook = new ExposureHook(evaluations::add);
    final HookContext<Object> context = context();
    final FlagEvaluationDetails<Object> details =
        details(
            ImmutableMetadata.builder()
                .addString("allocationKey", "allocation")
                .addBoolean("__dd_do_log", true)
                .addBoolean("__dd_exposure_cache_hit", false)
                .addString("__dd_feature.holdout.key", "holdout")
                .build());

    hook.finallyAfter(context, details, emptyMap());

    assertEquals(1, evaluations.size());
    final ExposureHook.Evaluation evaluation = evaluations.get(0);
    assertSame(context.getCtx(), evaluation.getContext());
    assertSame(details, evaluation.getDetails());
    assertTrue(evaluation.isExposure());
    assertFalse(evaluation.isCacheHit());
    assertTrue(evaluation.shouldSend());
    assertEquals(singletonMap("holdout.key", "holdout"), evaluation.getFeatures());
  }

  @Test
  void aliasReportsANonExposure() {
    final List<ExposureHook.Evaluation> evaluations = new ArrayList<>();
    final ExposureHook hook = new ExposureHook(evaluations::add);

    hook.finallyAfter(context(), details(ImmutableMetadata.builder().build()), emptyMap());

    final ExposureHook.Evaluation evaluation = evaluations.get(0);
    assertFalse(evaluation.isExposure());
    assertFalse(evaluation.isCacheHit());
    assertFalse(evaluation.shouldSend());
    assertTrue(evaluation.getFeatures().isEmpty());
  }

  @Test
  void aliasRejectsANullCallback() {
    assertThrows(NullPointerException.class, () -> new ExposureHook(null));
  }

  private static HookContext<Object> context() {
    return HookContext.from(
        "flag", FlagValueType.STRING, null, null, new ImmutableContext("user"), "default");
  }

  private static FlagEvaluationDetails<Object> details(final ImmutableMetadata metadata) {
    return FlagEvaluationDetails.builder()
        .flagKey("flag")
        .variant("on")
        .value("on")
        .flagMetadata(metadata)
        .build();
  }
}
