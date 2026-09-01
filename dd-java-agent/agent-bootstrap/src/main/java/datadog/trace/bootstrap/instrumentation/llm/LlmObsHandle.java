package datadog.trace.bootstrap.instrumentation.llm;

import datadog.trace.api.llmobs.LLMObs;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lifecycle handle for a single LLM operation. Drives JFR profiling events and LLMObs spans
 * independently — either backend may be absent when its product is not configured.
 *
 * <p>Sync usage:
 *
 * <pre>
 *   LlmObsHandle handle = INTEGRATION.startLlm(modelId);
 *   handle.withInput(messages);
 *   // ... LLM call ...
 *   handle.withOutput(response).withTokenMetrics(in, out).finish();
 * </pre>
 *
 * <p>Async / streaming usage — the JFR event is committed immediately as a correlation marker while
 * the LLMObs span stays open until the stream completes:
 *
 * <pre>
 *   LlmObsHandle handle = INTEGRATION.startLlm(modelId).withInput(messages).async();
 *   // hand handle to stream wrapper ...
 *   handle.withOutput(accumulated).withTokenMetrics(in, out).finish();
 * </pre>
 *
 * <p>Extension contract: subclasses implement {@link #onAsync()} and {@link #doFinish()} only. All
 * state accumulation and lifecycle enforcement is managed by this class.
 */
public abstract class LlmObsHandle {

  // finish() may be called from a different thread after async() hands off the handle
  private final AtomicBoolean done = new AtomicBoolean();
  private volatile boolean asyncMode;

  private List<LLMObs.LLMMessage> inputMessages;
  private List<LLMObs.LLMMessage> outputMessages;
  private String inputData;
  private String outputData;
  private boolean hasError;
  private Throwable thrown;
  private Integer inputTokens;
  private Integer outputTokens;

  /**
   * Switches to async mode. Triggers {@link #onAsync()} exactly once, then returns. The LLMObs span
   * stays open until {@link #finish()} is called from the stream completion thread. Calls after
   * {@link #finish()} are silently ignored.
   *
   * @return this handle, for chaining
   */
  public final LlmObsHandle async() {
    if (!done.get()) {
      asyncMode = true;
      onAsync();
    }
    return this;
  }

  /**
   * Sets structured input messages for LLM and embedding spans.
   *
   * @return this handle, for chaining
   */
  public final LlmObsHandle withInput(List<LLMObs.LLMMessage> messages) {
    inputMessages = messages;
    return this;
  }

  /**
   * Sets plain-text input for workflow and tool spans.
   *
   * @return this handle, for chaining
   */
  public final LlmObsHandle withInput(String data) {
    inputData = data;
    return this;
  }

  /**
   * Sets structured output messages.
   *
   * @return this handle, for chaining
   */
  public final LlmObsHandle withOutput(List<LLMObs.LLMMessage> messages) {
    outputMessages = messages;
    return this;
  }

  /**
   * Sets plain-text output.
   *
   * @return this handle, for chaining
   */
  public final LlmObsHandle withOutput(String data) {
    outputData = data;
    return this;
  }

  /**
   * Records token usage. Either value may be {@code null} when the model does not report it.
   *
   * @return this handle, for chaining
   */
  public final LlmObsHandle withTokenMetrics(Integer in, Integer out) {
    inputTokens = in;
    outputTokens = out;
    return this;
  }

  /**
   * Marks the operation as failed and attaches the throwable.
   *
   * @return this handle, for chaining
   */
  public final LlmObsHandle withError(Throwable t) {
    hasError = true;
    thrown = t;
    return this;
  }

  /**
   * Completes the operation. Calls {@link #doFinish()} exactly once regardless of how many times
   * this method is invoked. Thread-safe: safe to call from any thread after {@link #async()}.
   */
  public final void finish() {
    if (done.compareAndSet(false, true)) {
      doFinish();
    }
  }

  /**
   * Called at most once when {@link #async()} first transitions to async mode, before {@link
   * #doFinish()}. Override to commit a JFR event early as a correlation marker.
   */
  protected void onAsync() {}

  /**
   * Called exactly once by {@link #finish()}. Access accumulated state via the protected accessors
   * ({@link #isAsync()}, {@link #inputMessages()}, etc.).
   */
  protected abstract void doFinish();

  // --- Accessors for subclasses ---

  /** Whether {@link #async()} was called on this handle. */
  protected final boolean isAsync() {
    return asyncMode;
  }

  protected final List<LLMObs.LLMMessage> inputMessages() {
    return inputMessages;
  }

  protected final List<LLMObs.LLMMessage> outputMessages() {
    return outputMessages;
  }

  protected final String inputData() {
    return inputData;
  }

  protected final String outputData() {
    return outputData;
  }

  protected final boolean hasError() {
    return hasError;
  }

  protected final Throwable thrown() {
    return thrown;
  }

  protected final Integer inputTokens() {
    return inputTokens;
  }

  protected final Integer outputTokens() {
    return outputTokens;
  }

  /** No-op handle returned when all backends are disabled. */
  public static final LlmObsHandle NOOP =
      new LlmObsHandle() {
        @Override
        protected void doFinish() {}
      };
}
