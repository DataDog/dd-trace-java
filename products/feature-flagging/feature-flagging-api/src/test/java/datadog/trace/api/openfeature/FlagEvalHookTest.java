package datadog.trace.api.openfeature;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.ImmutableMetadata;
import dev.openfeature.sdk.Reason;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class FlagEvalHookTest {

  @Test
  void finallyAfterRecordsBasicEvaluation() {
    FlagEvalMetrics metrics = mock(FlagEvalMetrics.class);
    FlagEvalHook hook = new FlagEvalHook(metrics);

    FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder()
            .flagKey("my-flag")
            .value("on-value")
            .variant("on")
            .reason(Reason.TARGETING_MATCH.name())
            .flagMetadata(
                ImmutableMetadata.builder().addString("allocationKey", "default-alloc").build())
            .build();

    hook.finallyAfter(null, details, Collections.emptyMap());

    verify(metrics)
        .record(
            eq("my-flag"),
            eq("on"),
            eq(Reason.TARGETING_MATCH.name()),
            isNull(),
            eq("default-alloc"));
  }

  @Test
  void finallyAfterRecordsErrorEvaluation() {
    FlagEvalMetrics metrics = mock(FlagEvalMetrics.class);
    FlagEvalHook hook = new FlagEvalHook(metrics);

    FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder()
            .flagKey("missing-flag")
            .value("default")
            .reason(Reason.ERROR.name())
            .errorCode(ErrorCode.FLAG_NOT_FOUND)
            .build();

    hook.finallyAfter(null, details, Collections.emptyMap());

    verify(metrics)
        .record(
            eq("missing-flag"),
            isNull(),
            eq(Reason.ERROR.name()),
            eq(ErrorCode.FLAG_NOT_FOUND),
            isNull());
  }

  @Test
  void finallyAfterHandlesNullFlagMetadata() {
    FlagEvalMetrics metrics = mock(FlagEvalMetrics.class);
    FlagEvalHook hook = new FlagEvalHook(metrics);

    FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder()
            .flagKey("my-flag")
            .value(true)
            .variant("on")
            .reason(Reason.TARGETING_MATCH.name())
            .build();

    hook.finallyAfter(null, details, Collections.emptyMap());

    verify(metrics)
        .record(eq("my-flag"), eq("on"), eq(Reason.TARGETING_MATCH.name()), isNull(), isNull());
  }

  @Test
  void finallyAfterHandlesNullVariantAndReason() {
    FlagEvalMetrics metrics = mock(FlagEvalMetrics.class);
    FlagEvalHook hook = new FlagEvalHook(metrics);

    FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder().flagKey("my-flag").value("default").build();

    hook.finallyAfter(null, details, Collections.emptyMap());

    verify(metrics).record(eq("my-flag"), isNull(), isNull(), isNull(), isNull());
  }

  @Test
  void finallyAfterNeverThrows() {
    FlagEvalMetrics metrics = mock(FlagEvalMetrics.class);
    FlagEvalHook hook = new FlagEvalHook(metrics);

    // Should not throw even with completely null inputs
    hook.finallyAfter(null, null, null);

    verifyNoInteractions(metrics);
  }

  @Test
  void finallyAfterIsNoOpWhenMetricsIsNull() {
    FlagEvalHook hook = new FlagEvalHook(null);

    FlagEvaluationDetails<Object> details =
        FlagEvaluationDetails.<Object>builder()
            .flagKey("my-flag")
            .value(true)
            .variant("on")
            .reason(Reason.TARGETING_MATCH.name())
            .build();

    // Should not throw
    hook.finallyAfter(null, details, Collections.emptyMap());
  }
}
