package datadog.trace.instrumentation.llm.tck;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.trace.api.llmobs.LLMObs;
import datadog.trace.api.llmobs.LLMObsSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.llm.LlmCallHandle;
import java.util.List;
import jdk.jfr.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Technology compatibility kit for {@link LlmCallHandle}.
 *
 * <p>Every LLM framework instrumentation that builds on the {@link
 * datadog.trace.bootstrap.instrumentation.llm.LlmObsHandle} SPI must extend this class and
 * implement {@link #makeJfrEvent()} to verify the handle lifecycle contract.
 *
 * <p>Integration-factory-level testing (verifying that {@code startLlm} / {@code startWorkflow} /
 * {@code startTool} wire up the right spans end-to-end with real tracer/JFR/LLMObs
 * infrastructure) is left as a follow-up TCK.
 */
public abstract class LlmObsHandleTck {

  protected AgentScope scope;
  protected AgentSpan span;
  protected LLMObsSpan obsSpan;

  @BeforeEach
  void setUpMocks() {
    span = mock(AgentSpan.class);
    scope = mock(AgentScope.class);
    when(scope.span()).thenReturn(span);
    obsSpan = mock(LLMObsSpan.class);
  }

  /**
   * Return the JFR event class specific to the integration under test, with {@code begin()} already
   * called (as done by real advice).
   */
  protected abstract Event makeJfrEvent();

  // --- handle factories ---

  protected LlmCallHandle allBackends() {
    return new LlmCallHandle(makeJfrEvent(), obsSpan, scope);
  }

  protected LlmCallHandle apmOnly() {
    return new LlmCallHandle(null, null, scope);
  }

  protected LlmCallHandle obsOnly() {
    return new LlmCallHandle(null, obsSpan, null);
  }

  // --- lifecycle ---

  @Test
  void finishIsIdempotent() {
    LlmCallHandle handle = allBackends();
    handle.finish();
    handle.finish();
    verify(span, times(1)).finish();
    verify(obsSpan, times(1)).finish();
  }

  @Test
  void scopeClosedBeforeSpanFinished() {
    allBackends().finish();
    InOrder order = inOrder(scope, span);
    order.verify(scope).close();
    order.verify(span).finish();
  }

  @Test
  void scopeAlwaysClosedEvenWhenObsSpanThrows() {
    doThrow(new RuntimeException("obs failure")).when(obsSpan).finish();
    assertThrows(RuntimeException.class, () -> allBackends().finish());
    verify(scope).close();
  }

  // --- error propagation ---

  @Test
  void errorWithThrowablePropagatedToBothBackends() {
    RuntimeException err = new RuntimeException("boom");
    allBackends().withError(err).finish();
    verify(obsSpan).setError(true);
    verify(obsSpan).addThrowable(err);
    verify(span).setError(true);
    verify(span).addThrowable(err);
  }

  @Test
  void errorWithoutThrowableSetsErrorFlagOnBothBackends() {
    allBackends().withError(null).finish();
    verify(obsSpan).setError(true);
    verify(obsSpan, never()).addThrowable(any());
    verify(span).setError(true);
    verify(span, never()).addThrowable(any());
  }

  @Test
  void noErrorByDefaultOnBothBackends() {
    allBackends().finish();
    verify(obsSpan, never()).setError(true);
    verify(span, never()).setError(true);
  }

  // --- I/O forwarding ---

  @Test
  void tokenMetricsForwardedToObsSpan() {
    allBackends().withTokenMetrics(3, 7).finish();
    verify(obsSpan).setMetric("input_tokens", 3);
    verify(obsSpan).setMetric("output_tokens", 7);
  }

  @Test
  void structuredMessagesForwardedToObsSpan() {
    List<LLMObs.LLMMessage> inputs = List.of(LLMObs.LLMMessage.from("user", "hello"));
    List<LLMObs.LLMMessage> outputs = List.of(LLMObs.LLMMessage.from("assistant", "hi"));
    allBackends().withInput(inputs).withOutput(outputs).finish();
    verify(obsSpan).annotateIO(inputs, outputs);
  }

  @Test
  void plainTextIoForwardedToObsSpan() {
    allBackends().withInput("hello").withOutput("hi").finish();
    verify(obsSpan).annotateIO("hello", "hi");
  }

  // --- null-safety ---

  @Test
  void nullJfrEventHandledGracefully() {
    assertDoesNotThrow(() -> new LlmCallHandle(null, obsSpan, scope).finish());
  }

  @Test
  void nullObsSpanHandledGracefully() {
    assertDoesNotThrow(() -> new LlmCallHandle(makeJfrEvent(), null, scope).finish());
  }

  @Test
  void nullScopeHandledGracefully() {
    assertDoesNotThrow(() -> new LlmCallHandle(makeJfrEvent(), obsSpan, null).finish());
  }

  @Test
  void allNullHandleFinishesGracefully() {
    assertDoesNotThrow(() -> new LlmCallHandle(null, null, null).finish());
  }

  // --- async lifecycle ---

  @Test
  void asyncClosesscopeOnEntryAndNotAgainOnFinish() {
    LlmCallHandle handle = allBackends();
    handle.async();
    verify(scope, times(1)).close(); // closed on entering thread
    handle.finish();
    verify(scope, times(1)).close(); // not closed a second time in doFinish
    verify(span, times(1)).finish(); // span still finished
  }
}
