package datadog.trace.api.aiguard;

import datadog.trace.api.aiguard.noop.NoOpEvaluator;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
    final List<String> tags;

    /**
     * Creates a new evaluation result.
     *
     * @param action the recommended action for the evaluated content
     * @param reason human-readable explanation for the decision
     * @param tags list of tags associated with the evaluation (e.g. indirect-prompt-injection)
     */
    public Evaluation(final Action action, final String reason, final List<String> tags) {
      this.action = action;
      this.reason = reason;
      this.tags = tags;
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

    /**
     * Returns the list of tags associated with the evaluation (e.g. indirect-prompt-injection)
     *
     * @return list of tags.
     */
    public List<String> getTags() {
      return tags;
    }
  }

  /**
   * Represents an image URL in a content part. Images can be provided as URLs or data URIs.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // Image from URL
   * var imageUrl = new AIGuard.ImageURL("https://example.com/image.jpg");
   *
   * // Image from data URI
   * var dataUri = new AIGuard.ImageURL("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUA...");
   * }</pre>
   */
  public static class ImageURL {

    private final String url;

    /**
     * Creates a new ImageURL with the specified URL.
     *
     * @param url the image URL or data URI
     * @throws NullPointerException if url is null
     */
    public ImageURL(@Nonnull final String url) {
      this.url = Objects.requireNonNull(url, "url cannot be null");
    }

    /**
     * Returns the image URL.
     *
     * @return the image URL or data URI
     */
    @Nonnull
    public String getUrl() {
      return url;
    }
  }

  /**
   * Represents a content part within a message. Content parts can be text or images, enabling
   * multimodal AI prompts.
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // Text content
   * var textPart = AIGuard.ContentPart.text("Describe this image:");
   *
   * // Image content from URL
   * var imagePart = AIGuard.ContentPart.imageUrl("https://example.com/image.jpg");
   *
   * // Multimodal message with text and image
   * var message = AIGuard.Message.message("user", List.of(textPart, imagePart));
   * }</pre>
   */
  public static class ContentPart {

    /** Type of content part. */
    public enum Type {
      /** Text content */
      TEXT,
      /** Image URL content */
      IMAGE_URL;

      @Override
      public String toString() {
        return name().toLowerCase(Locale.ROOT);
      }
    }

    private final Type type;
    @Nullable private final String text;
    @Nullable private final ImageURL imageUrl;

    /**
     * Private constructor to enforce use of factory methods.
     *
     * @param type the content part type
     * @param text the text content (required for TEXT type)
     * @param imageUrl the image URL (required for IMAGE_URL type)
     */
    private ContentPart(
        @Nonnull final Type type, @Nullable final String text, @Nullable final ImageURL imageUrl) {
      this.type = type;
      this.text = text;
      this.imageUrl = imageUrl;

      if (type == Type.TEXT && text == null) {
        throw new IllegalArgumentException("text content part requires text field");
      }
      if (type == Type.IMAGE_URL && imageUrl == null) {
        throw new IllegalArgumentException("image_url content part requires imageUrl field");
      }
    }

    /**
     * Returns the type of this content part.
     *
     * @return the content part type (TEXT or IMAGE_URL)
     */
    @Nonnull
    public Type getType() {
      return type;
    }

    /**
     * Returns the text content of this part.
     *
     * @return the text content, or null if this is an IMAGE_URL part
     */
    @Nullable
    public String getText() {
      return text;
    }

    /**
     * Returns the image URL of this part.
     *
     * @return the image URL, or null if this is a TEXT part
     */
    @Nullable
    public ImageURL getImageUrl() {
      return imageUrl;
    }

    /**
     * Creates a text content part.
     *
     * @param text the text content
     * @return a new ContentPart with TEXT type
     * @throws NullPointerException if text is null
     */
    @Nonnull
    public static ContentPart text(@Nonnull final String text) {
      Objects.requireNonNull(text, "text cannot be null");
      return new ContentPart(Type.TEXT, text, null);
    }

    /**
     * Creates an image content part from a URL string.
     *
     * @param url the image URL or data URI
     * @return a new ContentPart with IMAGE_URL type
     * @throws NullPointerException if url is null
     */
    @Nonnull
    public static ContentPart imageUrl(@Nonnull final String url) {
      return new ContentPart(Type.IMAGE_URL, null, new ImageURL(url));
    }
  }

  /**
   * Represents a message in an AI conversation. Messages can represent user prompts, assistant
   * responses, system messages, or tool outputs.
   *
   * <p>Messages can contain either plain text content or structured content parts (text and
   * images):
   *
   * <p>Example usage:
   *
   * <pre>{@code
   * // User prompt with text
   * var userPrompt = AIGuard.Message.message("user", "What's the weather like?");
   *
   * // User prompt with text and image
   * var multimodalPrompt = AIGuard.Message.message("user", List.of(
   *     AIGuard.ContentPart.text("Describe this image:"),
   *     AIGuard.ContentPart.imageUrl("https://example.com/image.jpg")
   * ));
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
    @Nullable private final String content;
    @Nullable private final List<ContentPart> contentParts;
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
        @Nonnull final String role,
        @Nullable final String content,
        @Nullable final List<ToolCall> toolCalls,
        @Nullable final String toolCallId) {
      this.role = role;
      this.content = content;
      this.contentParts = null;
      this.toolCalls = toolCalls;
      this.toolCallId = toolCallId;
    }

    /**
     * Creates a new message with content parts (text and/or images).
     *
     * @param role the role of the message sender
     * @param contentParts list of content parts
     * @param toolCalls list of tool calls, or null
     * @param toolCallId the tool call ID this message responds to, or null
     * @throws IllegalArgumentException if contentParts contains null elements
     */
    public Message(
        @Nonnull final String role,
        @Nonnull final List<ContentPart> contentParts,
        @Nullable final List<ToolCall> toolCalls,
        @Nullable final String toolCallId) {
      this.role = role;
      this.content = null;

      for (final ContentPart part : contentParts) {
        if (part == null) {
          throw new IllegalArgumentException("contentParts must not contain null elements");
        }
      }

      this.contentParts = Collections.unmodifiableList(contentParts);
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
     * @return the message content, or null if using content parts or for assistant messages with
     *     only tool calls
     */
    @Nullable
    public String getContent() {
      return content;
    }

    /**
     * Returns the content parts of the message.
     *
     * @return the content parts (text and images), or null if using plain text content
     */
    @Nullable
    public List<ContentPart> getContentParts() {
      return contentParts;
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
    @Nonnull
    public static Message message(@Nonnull final String role, @Nonnull final String content) {
      return new Message(role, content, null, null);
    }

    /**
     * Creates a message with specified role and content parts (text and/or images).
     *
     * @param role the role of the message sender (e.g., "user", "system")
     * @param contentParts list of content parts (text and/or images)
     * @return a new Message instance
     */
    @Nonnull
    public static Message message(
        @Nonnull final String role, @Nonnull final List<ContentPart> contentParts) {
      return new Message(role, contentParts, null, null);
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
    @Nonnull
    public static Message assistant(@Nonnull final ToolCall... toolCalls) {
      return new Message("assistant", (String) null, Arrays.asList(toolCalls), null);
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
