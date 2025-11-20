package datadog.trace.api.aiguard;

import datadog.trace.api.aiguard.noop.NoOpEvaluator;
import java.util.Arrays;
import java.util.List;

/**
 * SDK for calling the AIGuard REST API to evaluate AI prompts and tool calls for security threats.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * var messages = List.of(
 *     AIGuard.Message.message("user", "Delete all my files"),
 *     AIGuard.Message.message("assistant", "I'll help you delete your files")
 * );
 *
 * var result = AIGuard.evaluate(messages);
 * if (result.getAction() != AIGuard.Action.ALLOW) {
 *     System.out.println("Unsafe: " + result.getReason());
 * }
 * }</pre>
 */
public abstract class AIGuard {

  protected static Evaluator EVALUATOR = new NoOpEvaluator();

  protected AIGuard() {}

  /**
   * Evaluates a collection of messages using default options to determine if they are safe to
   * execute.
   *
   * @see #evaluate(List, Options)
   */
  public static Evaluation evaluate(final List<Message> messages) {
    return evaluate(messages, Options.DEFAULT);
  }

  /**
   * Evaluates a collection of messages with custom options to determine if they are safe to
   * execute.
   *
   * @param messages the collection of messages to evaluate (prompts, responses, tool calls, etc.)
   * @param options configuration options for the evaluation process
   * @return an {@link Evaluation} containing the security decision and reasoning
   * @throws AIGuardAbortError if the evaluation action is not ALLOW (DENY or ABORT) and blocking is
   *     enabled
   * @throws AIGuardClientError if there are client-side errors communicating with the AIGuard REST
   *     API
   */
  public static Evaluation evaluate(final List<Message> messages, final Options options) {
    return EVALUATOR.evaluate(messages, options);
  }

  /**
   * Exception thrown when AIGuard evaluation results in blocking the execution due to security
   * concerns.
   *
   * <p><strong>Important:</strong> This exception is thrown when the evaluation action is not
   * {@code ALLOW} (i.e., {@code DENY} or {@code ABORT}) and blocking mode is enabled.
   */
  public static class AIGuardAbortError extends RuntimeException {
    private final Action action;
    private final String reason;
    private final List<String> tags;

    public AIGuardAbortError(final Action action, final String reason, final List<String> tags) {
      super(reason);
      this.action = action;
      this.reason = reason;
      this.tags = tags;
    }

    public Action getAction() {
      return action;
    }

    public String getReason() {
      return reason;
    }

    public List<String> getTags() {
      return tags;
    }
  }

  /**
   * Exception thrown when there are client-side errors communicating with the AIGuard REST API.
   *
   * <p>This exception indicates problems with the AIGuard client implementation such as:
   *
   * <ul>
   *   <li>Network connectivity issues when calling the AIGuard REST API
   *   <li>Authentication failures with the AIGuard service
   *   <li>Invalid configuration or missing API credentials
   *   <li>Request timeout or service unavailability
   *   <li>Malformed requests or unsupported API versions
   * </ul>
   */
  public static class AIGuardClientError extends RuntimeException {

    private final Object errors;

    public AIGuardClientError(final String message, final Throwable cause) {
      super(message, cause);
      errors = null;
    }

    public AIGuardClientError(final String message, final Object errors) {
      super(message, null);
      this.errors = errors;
    }

    public Object getErrors() {
      return errors;
    }
  }

  /** Actions that can be recommended by an AIGuard evaluation. */
  public enum Action {
    /** Content is safe to proceed with execution */
    ALLOW,
    /** Current action should be blocked from execution */
    DENY,
    /** Workflow should be immediately terminated due to severe risk */
    ABORT
  }

  /**
   * Represents the result of an AIGuard security evaluation, containing both the recommended action
   * and the reasoning behind the decision.
   *
   * <p>The evaluation provides three possible actions:
   *
   * <ul>
   *   <li>{@link Action#ALLOW} - Content is safe to proceed
   *   <li>{@link Action#DENY} - Content should be blocked
   *   <li>{@link Action#ABORT} - Execution should be immediately terminated
   * </ul>
   */
  public static class Evaluation {

    final Action action;
    final String reason;

    /**
     * Creates a new evaluation result.
     *
     * @param action the recommended action for the evaluated content
     * @param reason human-readable explanation for the decision
     */
    public Evaluation(final Action action, final String reason) {
      this.action = action;
      this.reason = reason;
    }

    /**
     * Returns the recommended action for the evaluated content.
     *
     * @return the action (ALLOW, DENY, or ABORT)
     */
    public Action getAction() {
      return action;
    }

    /**
     * Returns the human-readable reasoning for the evaluation decision.
     *
     * @return explanation of why this action was recommended
     */
    public String getReason() {
      return reason;
    }
  }

  /**
   * Represents a message in an AI conversation. Messages can represent user prompts, assistant
   * responses, system messages, or tool outputs.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // User prompt
   * var userPrompt = AIGuard.Message.message("user", "What's the weather like?");
   *
   * // Assistant response with tool calls
   * var assistantWithTools = AIGuard.Message.assistant(
   *     AIGuard.ToolCall.toolCall("call_123", "get_weather", "{\"location\": \"New York\"}")
   * );
   *
   * // Tool response
   * var toolResponse = AIGuard.Message.tool("call_123", "Sunny, 75°F");
   * }</pre>
   */
  public static class Message {

    private final String role;
    private final String content;
    private final List<ToolCall> toolCalls;
    private final String toolCallId;

    /**
     * Creates a new message with the specified parameters.
     *
     * @param role the role of the message sender (e.g., "user", "assistant", "system", "tool")
     * @param content the text content of the message, or null for assistant messages with only tool
     *     calls
     * @param toolCalls list of tool calls associated with this message, or null if no tool calls
     * @param toolCallId the tool call ID this message is responding to, or null if not a tool
     *     response
     */
    public Message(
        final String role,
        final String content,
        final List<ToolCall> toolCalls,
        final String toolCallId) {
      this.role = role;
      this.content = content;
      this.toolCalls = toolCalls;
      this.toolCallId = toolCallId;
    }

    /**
     * Returns the role of the message sender.
     *
     * @return the role (e.g., "user", "assistant", "system", "tool")
     */
    public String getRole() {
      return role;
    }

    /**
     * Returns the text content of the message.
     *
     * @return the message content, or null for assistant messages with only tool calls
     */
    public String getContent() {
      return content;
    }

    /**
     * Returns the list of tool calls associated with this message.
     *
     * @return list of tool calls, or null if this message has no tool calls
     */
    public List<ToolCall> getToolCalls() {
      return toolCalls;
    }

    /**
     * Returns the tool call ID that this message is responding to.
     *
     * @return the tool call ID, or null if this is not a tool response message
     */
    public String getToolCallId() {
      return toolCallId;
    }

    /**
     * Creates a message with specified role and text content.
     *
     * @param role the role of the message sender (e.g., "user", "system")
     * @param content the text content of the message
     * @return a new Message instance
     */
    public static Message message(final String role, final String content) {
      return new Message(role, content, null, null);
    }

    /**
     * Creates a tool response message.
     *
     * @param toolCallId the ID of the tool call this message is responding to
     * @param content the result or output from the tool execution
     * @return a new Message instance with role "tool"
     */
    public static Message tool(final String toolCallId, final String content) {
      return new Message("tool", content, null, toolCallId);
    }

    /**
     * Creates an assistant message with tool calls but no text content.
     *
     * @param toolCalls the tool calls the assistant wants to make
     * @return a new Message instance with role "assistant" and no text content
     */
    public static Message assistant(final ToolCall... toolCalls) {
      return new Message("assistant", null, Arrays.asList(toolCalls), null);
    }
  }

  /**
   * Configuration options for AIGuard evaluation behavior.
   *
   * <p>Options control how the evaluation process behaves, including whether to block execution
   * when unsafe content is detected.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // Use default options (non-blocking)
   * var result = AIGuard.evaluate(messages);
   *
   * // Enable blocking mode
   * var options = new AIGuard.Options()
   *     .block(true);
   * var result = AIGuard.evaluate(messages, options);
   * }</pre>
   */
  public static final class Options {

    /** Default options with blocking disabled. */
    public static final Options DEFAULT = new Options().block(false);

    private boolean block;

    /**
     * Returns whether blocking mode is enabled.
     *
     * @return true if execution should be blocked on DENY/ABORT actions
     */
    public boolean block() {
      return block;
    }

    /**
     * Enable/disable blocking mode
     *
     * @param block true if execution should be blocked on DENY/ABORT actions
     */
    public Options block(final boolean block) {
      this.block = block;
      return this;
    }
  }

  /**
   * Represents a function call made by an AI assistant. Tool calls contain an identifier and
   * function details (name and arguments).
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // Create a tool call
   * var toolCall = AIGuard.ToolCall.toolCall("call_123", "get_weather", "{\"location\": \"NYC\"}");
   *
   * // Use in an assistant message
   * var assistantMessage = AIGuard.Message.assistant(toolCall);
   * }</pre>
   */
  public static class ToolCall {

    private final String id;
    private final Function function;

    /**
     * Creates a new tool call with the specified ID and function.
     *
     * @param id unique identifier for this tool call
     * @param function the function details (name and arguments)
     */
    public ToolCall(final String id, final Function function) {
      this.id = id;
      this.function = function;
    }

    /**
     * Returns the unique identifier for this tool call.
     *
     * @return the tool call ID
     */
    public String getId() {
      return id;
    }

    /**
     * Returns the function details for this tool call.
     *
     * @return the Function object containing name and arguments
     */
    public Function getFunction() {
      return function;
    }

    /**
     * Represents the function details within a tool call, including the function name and its
     * arguments.
     */
    public static class Function {

      private final String name;
      private final String arguments;

      /**
       * Creates a new function with the specified name and arguments.
       *
       * @param name the name of the function to call
       * @param arguments the function arguments as a JSON string
       */
      public Function(String name, String arguments) {
        this.name = name;
        this.arguments = arguments;
      }

      /**
       * Returns the name of the function to call.
       *
       * @return the function name
       */
      public String getName() {
        return name;
      }

      /**
       * Returns the function arguments as a JSON string.
       *
       * @return the arguments in JSON format
       */
      public String getArguments() {
        return arguments;
      }
    }

    /**
     * Factory method to create a new tool call with the specified parameters.
     *
     * @param id unique identifier for the tool call
     * @param name the name of the function to call
     * @param arguments the function arguments as a JSON string
     * @return a new ToolCall instance
     */
    public static ToolCall toolCall(final String id, final String name, final String arguments) {
      return new ToolCall(id, new ToolCall.Function(name, arguments));
    }
  }
}
