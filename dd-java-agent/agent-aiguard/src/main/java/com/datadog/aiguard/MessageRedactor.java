package com.datadog.aiguard;

import datadog.trace.api.aiguard.AIGuard.ContentPart;
import datadog.trace.api.aiguard.AIGuard.Message;
import datadog.trace.api.aiguard.AIGuard.ToolCall;
import datadog.trace.api.aiguard.AIGuard.ToolCall.Function;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

public interface MessageRedactor {

  /** Outcome of a redaction pass. */
  final class Result {

    /** The redacted messages, or the very same list that was passed in when nothing was applied. */
    final List<Message> messages;

    /**
     * The {@code {path, replacement}} entries that were actually overwritten, in the order the
     * service returned them. Entries skipped fail-safe never appear here, so this list describes
     * exactly the transformation {@link #messages} underwent, and it is what the SDK hands back
     * through {@code Evaluation.getRedactionReplacements()}.
     */
    final List<Map<String, String>> replacements;

    /** Number of paths successfully overwritten. */
    final int applied;

    /** Number of entries skipped fail-safe (unresolvable, non-string, missing or conflicting). */
    final int skipped;

    private Result(
        final List<Message> messages,
        final List<Map<String, String>> replacements,
        final int skipped) {
      this.messages = messages;
      this.replacements = replacements;
      this.applied = replacements.size();
      this.skipped = skipped;
    }

    /** Whether at least one replacement was applied. */
    boolean redacted() {
      return applied > 0;
    }

    private static Result nothingApplied(final List<Message> messages, final int skipped) {
      return new Result(messages, Collections.emptyList(), skipped);
    }
  }

  Result redact(final List<Message> messages, @Nullable final List<?> replacements);

  /**
   * Whether redaction is active. When {@code false} the {@code ai_guard.redacted} tag is not
   * reported at all, so an absent tag means "redaction is off", which stays distinguishable from a
   * {@code false} one meaning "redaction is on and nothing was redacted".
   */
  boolean enabled();

  class NoOp implements MessageRedactor {

    @Override
    public Result redact(final List<Message> messages, final @Nullable List<?> replacements) {
      return Result.nothingApplied(messages, 0);
    }

    @Override
    public boolean enabled() {
      return false;
    }
  }

  /**
   * Applies the {@code redaction_replacements} returned by the AI Guard service to a message list.
   *
   * <p>The service returns the <em>fully redacted</em> string for each affected path, so this class
   * never slices, concatenates, or reasons about offsets and string encodings: it resolves a path
   * to a single string and overwrites it verbatim. Placeholder selection and redaction strategy are
   * resolved server side and already baked into each replacement.
   *
   * <p>Two properties matter to callers:
   *
   * <ul>
   *   <li><strong>Copy on write.</strong> The caller's list and messages are never mutated. Only
   *       the messages that are actually redacted are rebuilt; the rest are shared with the input
   *       list. When nothing is applied, {@link Result#messages} is the very same reference that
   *       was passed in, so {@code result.messages != messages} tells the caller whether anything
   *       changed.
   *   <li><strong>Never throws.</strong> A malformed response must not break the caller's control
   *       flow, so unresolvable paths, non-string targets, and missing or conflicting replacements
   *       are skipped and counted in {@link Result#skipped}.
   * </ul>
   */
  class DefaultRedactor implements MessageRedactor {

    @Override
    public boolean enabled() {
      return true;
    }

    /**
     * Matches a single path segment, e.g. {@code messages[1]} or {@code function}. Kept verbatim
     * from the cross-tracer specification, so every tracer tokenizes paths identically.
     */
    private static final Pattern SEGMENT =
        Pattern.compile("\\A([A-Za-z0-9_]+)(?:\\[([0-9]+)\\])?\\z");

    /** No supported target is deeper than {@code messages[i].tool_calls[k].function.arguments}. */
    private static final int MAX_SEGMENTS = 4;

    private static final int NO_INDEX = -1;
    private static final int INVALID_INDEX = -2;

    /** Longest index we bother parsing; anything longer cannot address a real list. */
    private static final int MAX_INDEX_DIGITS = 9;

    private static Map<String, String> entry(final String path, final String replacement) {
      final Map<String, String> entry = new LinkedHashMap<>(4);
      entry.put("path", path);
      entry.put("replacement", replacement);
      return Collections.unmodifiableMap(entry);
    }

    /**
     * Overwrites every path in {@code replacements} with its replacement string.
     *
     * @param messages the evaluated messages, never mutated
     * @param replacements the raw {@code redaction_replacements} array from the response
     * @return the redaction outcome, holding {@code messages} itself when nothing was applied
     */
    @Override
    public Result redact(final List<Message> messages, @Nullable final List<?> replacements) {
      if (messages == null
          || messages.isEmpty()
          || replacements == null
          || replacements.isEmpty()) {
        return Result.nothingApplied(messages, 0);
      }

      int skipped = 0;

      // Collect one authoritative replacement per path, dropping the ones the backend contradicts
      // itself on. Insertion ordered so that a malformed response yields reproducible counters.
      final Map<String, String> byPath = new LinkedHashMap<>();
      Set<String> conflicting = null;
      for (final Object entry : replacements) {
        if (!(entry instanceof Map)) {
          skipped++;
          continue;
        }
        final Map<?, ?> map = (Map<?, ?>) entry;
        final Object path = map.get("path");
        final Object replacement = map.get("replacement");
        // An empty replacement is legitimate: "" is a supported placeholder meaning "remove".
        if (!(path instanceof String)
            || ((String) path).isEmpty()
            || !(replacement instanceof String)) {
          skipped++;
          continue;
        }
        final String previous = byPath.put((String) path, (String) replacement);
        if (previous != null && !previous.equals(replacement)) {
          // Conflicting replacements for one path: skip it rather than guess which one wins.
          if (conflicting == null) {
            conflicting = new HashSet<>(2);
          }
          conflicting.add((String) path);
        }
      }
      if (conflicting != null) {
        for (final String path : conflicting) {
          byPath.remove(path);
          skipped++;
        }
      }
      if (byPath.isEmpty()) {
        return Result.nothingApplied(messages, skipped);
      }

      final String[] names = new String[MAX_SEGMENTS];
      final int[] indices = new int[MAX_SEGMENTS];
      List<Message> working = null;
      final List<Map<String, String>> applied = new ArrayList<>(byPath.size());

      for (final Map.Entry<String, String> entry : byPath.entrySet()) {
        final int count = parseSegments(entry.getKey(), names, indices);
        // Paths are rooted at the evaluated array, so they always start with `messages[i]`.
        if (count < 2
            || indices[0] == NO_INDEX
            || !"messages".equals(names[0])
            || indices[0] >= messages.size()) {
          skipped++;
          continue;
        }
        final int index = indices[0];
        final Message current = working == null ? messages.get(index) : working.get(index);
        final Message updated = apply(current, names, indices, count, entry.getValue());
        if (updated == null) {
          skipped++;
          continue;
        }
        if (working == null) {
          working = new ArrayList<>(messages);
        }
        working.set(index, updated);
        applied.add(entry(entry.getKey(), entry.getValue()));
      }

      if (working == null) {
        return Result.nothingApplied(messages, skipped);
      }
      return new Result(working, Collections.unmodifiableList(applied), skipped);
    }

    /**
     * Rebuilds {@code message} with {@code replacement} written at the target the path resolves to.
     *
     * @return the rebuilt message, or {@code null} when the path does not resolve to a writable
     *     string, in which case the caller skips it fail-safe
     */
    @Nullable
    private static Message apply(
        final Message message,
        final String[] names,
        final int[] indices,
        final int count,
        final String replacement) {

      if ("content".equals(names[1])) {
        if (indices[1] == NO_INDEX) {
          // messages[i].content
          if (count != 2 || message.getContentParts() != null || message.getContent() == null) {
            // A message holding content parts resolves to a list, not a string: skip.
            return null;
          }
          return withContent(message, replacement);
        }
        // messages[i].content[j].text
        if (count != 3 || !"text".equals(names[2]) || indices[2] != NO_INDEX) {
          return null;
        }
        final List<ContentPart> parts = message.getContentParts();
        if (parts == null || indices[1] >= parts.size()) {
          return null;
        }
        final ContentPart part = parts.get(indices[1]);
        // Only text parts are redactable; image locators are out of scope.
        if (part.getType() != ContentPart.Type.TEXT) {
          return null;
        }
        final List<ContentPart> updated = new ArrayList<>(parts);
        updated.set(indices[1], ContentPart.text(replacement));
        return withContentParts(message, updated);
      }

      if ("tool_calls".equals(names[1])) {
        // messages[i].tool_calls[k].function.arguments
        if (indices[1] == NO_INDEX
            || count != 4
            || !"function".equals(names[2])
            || indices[2] != NO_INDEX
            || !"arguments".equals(names[3])
            || indices[3] != NO_INDEX) {
          return null;
        }
        final List<ToolCall> toolCalls = message.getToolCalls();
        if (toolCalls == null || indices[1] >= toolCalls.size()) {
          return null;
        }
        final ToolCall toolCall = toolCalls.get(indices[1]);
        final Function function = toolCall.getFunction();
        if (function == null || function.getArguments() == null) {
          return null;
        }
        final List<ToolCall> updated = new ArrayList<>(toolCalls);
        updated.set(
            indices[1],
            new ToolCall(toolCall.getId(), new Function(function.getName(), replacement)));
        return withToolCalls(message, updated);
      }

      return null;
    }

    private static Message withContent(final Message message, final String content) {
      return new Message(
          message.getRole(), content, message.getToolCalls(), message.getToolCallId());
    }

    private static Message withContentParts(
        final Message message, final List<ContentPart> contentParts) {
      return new Message(
          message.getRole(), contentParts, message.getToolCalls(), message.getToolCallId());
    }

    private static Message withToolCalls(final Message message, final List<ToolCall> toolCalls) {
      final List<ContentPart> contentParts = message.getContentParts();
      // A message carries either content parts or a content string, never both; preserve whichever.
      return contentParts != null
          ? new Message(message.getRole(), contentParts, toolCalls, message.getToolCallId())
          : new Message(
              message.getRole(), message.getContent(), toolCalls, message.getToolCallId());
    }

    /**
     * Splits a path on {@code .} and matches every segment against {@link #SEGMENT}, filling {@code
     * names} and {@code indices}.
     *
     * @return the number of segments, or {@code -1} when any segment is malformed or the path is
     *     deeper than any supported target
     */
    private static int parseSegments(final String path, final String[] names, final int[] indices) {
      int count = 0;
      int start = 0;
      while (true) {
        if (count == MAX_SEGMENTS) {
          return -1;
        }
        final int dot = path.indexOf('.', start);
        final String segment = dot < 0 ? path.substring(start) : path.substring(start, dot);
        final Matcher matcher = SEGMENT.matcher(segment);
        if (!matcher.matches()) {
          return -1;
        }
        final int index = parseIndex(matcher.group(2));
        if (index == INVALID_INDEX) {
          return -1;
        }
        names[count] = matcher.group(1);
        indices[count] = index;
        count++;
        if (dot < 0) {
          return count;
        }
        start = dot + 1;
      }
    }

    private static int parseIndex(@Nullable final String index) {
      if (index == null) {
        return NO_INDEX;
      }
      // The regex guarantees digits only, so the sole remaining hazard is overflow.
      return index.length() > MAX_INDEX_DIGITS ? INVALID_INDEX : Integer.parseInt(index);
    }
  }
}
