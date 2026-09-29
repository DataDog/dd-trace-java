package com.datadog.aiguard;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.aiguard.AIGuard.ContentPart;
import datadog.trace.api.aiguard.AIGuard.Message;
import datadog.trace.api.aiguard.AIGuard.ToolCall;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MessageRedactorTest {

  private static final MessageRedactor REDACTOR = new MessageRedactor.DefaultRedactor();

  private static Map<String, Object> replacement(final Object path, final Object replacement) {
    final Map<String, Object> entry = new HashMap<>(2);
    entry.put("path", path);
    entry.put("replacement", replacement);
    return entry;
  }

  private static List<Message> messages(final Message... messages) {
    return new ArrayList<>(asList(messages));
  }

  @Nested
  class ContentString {

    @Test
    void redactsPlainContent() {
      final List<Message> messages =
          messages(
              Message.message("system", "You are a helpful assistant."),
              Message.message("user", "My SSN is 123-45-6789"));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages, singletonList(replacement("messages[1].content", "My SSN is <REDACTED>")));

      assertTrue(result.redacted());
      assertEquals(1, result.applied);
      assertEquals(0, result.skipped);
      assertEquals("My SSN is <REDACTED>", result.messages.get(1).getContent());
      // the untouched message is shared, not rebuilt
      assertSame(messages.get(0), result.messages.get(0));
    }

    @Test
    void neverMutatesTheCallersMessages() {
      final Message original = Message.message("user", "My SSN is 123-45-6789");
      final List<Message> messages = messages(original);

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages, singletonList(replacement("messages[0].content", "My SSN is <REDACTED>")));

      assertNotSame(messages, result.messages);
      assertEquals("My SSN is 123-45-6789", original.getContent());
      assertEquals("My SSN is 123-45-6789", messages.get(0).getContent());
    }

    @Test
    void preservesRoleAndToolCallId() {
      final List<Message> messages = messages(Message.tool("call_1", "Account 000123456789"));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages, singletonList(replacement("messages[0].content", "Account <REDACTED>")));

      final Message redacted = result.messages.get(0);
      assertEquals("tool", redacted.getRole());
      assertEquals("call_1", redacted.getToolCallId());
      assertEquals("Account <REDACTED>", redacted.getContent());
    }

    @Test
    void appliesEmptyReplacementBecauseItMeansRemoval() {
      final List<Message> messages = messages(Message.message("user", "My SSN is 123-45-6789"));

      final MessageRedactor.Result result =
          REDACTOR.redact(messages, singletonList(replacement("messages[0].content", "")));

      assertTrue(result.redacted());
      assertEquals(0, result.skipped);
      assertEquals("", result.messages.get(0).getContent());
    }

    @Test
    void copiesReplacementVerbatimIncludingAstralPlaneCharacters() {
      final String replacement = "🎭 <REDACTED> 👨‍👩‍👧‍👦 café";
      final List<Message> messages =
          messages(Message.message("user", "🎭 123-45-6789 👨‍👩‍👧‍👦 café"));

      final MessageRedactor.Result result =
          REDACTOR.redact(messages, singletonList(replacement("messages[0].content", replacement)));

      assertEquals(replacement, result.messages.get(0).getContent());
    }
  }

  @Nested
  class ContentParts {

    @Test
    void redactsTextContentPart() {
      final List<Message> messages =
          messages(
              Message.message(
                  "user",
                  asList(
                      ContentPart.text("here is my card 4111111111111111"),
                      ContentPart.imageUrl("https://example.com/image.jpg"))));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              singletonList(
                  replacement("messages[0].content[0].text", "here is my card <REDACTED>")));

      assertTrue(result.redacted());
      final List<ContentPart> parts = result.messages.get(0).getContentParts();
      assertEquals("here is my card <REDACTED>", parts.get(0).getText());
      // the image part is untouched
      assertEquals("https://example.com/image.jpg", parts.get(1).getImageUrl().getUrl());
    }

    @Test
    void skipsImageUrlPart() {
      final List<Message> messages =
          messages(
              Message.message(
                  "user", singletonList(ContentPart.imageUrl("https://example.com/image.jpg"))));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages, singletonList(replacement("messages[0].content[0].text", "<REDACTED>")));

      assertFalse(result.redacted());
      assertEquals(1, result.skipped);
      assertSame(messages, result.messages);
    }

    @Test
    void skipsContentPartPathWhenMessageHoldsAPlainString() {
      // messages[0].content[0].text has nothing to resolve against
      final List<Message> messages = messages(Message.message("user", "plain content"));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages, singletonList(replacement("messages[0].content[0].text", "<REDACTED>")));

      assertFalse(result.redacted());
      assertEquals(1, result.skipped);
      assertSame(messages, result.messages);
    }

    @Test
    void skipsContentPathWhenMessageHoldsContentParts() {
      // messages[0].content resolves to a list, not a string
      final List<Message> messages =
          messages(Message.message("user", singletonList(ContentPart.text("secret"))));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages, singletonList(replacement("messages[0].content", "<REDACTED>")));

      assertFalse(result.redacted());
      assertEquals(1, result.skipped);
      assertSame(messages, result.messages);
    }
  }

  @Nested
  class ToolCallArguments {

    @Test
    void redactsToolCallArguments() {
      final List<Message> messages =
          messages(
              Message.assistant(
                  ToolCall.toolCall("call_1", "send_email", "{\"ssn\":\"123-45-6789\"}")));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              singletonList(
                  replacement(
                      "messages[0].tool_calls[0].function.arguments", "{\"ssn\":\"<REDACTED>\"}")));

      assertTrue(result.redacted());
      final ToolCall toolCall = result.messages.get(0).getToolCalls().get(0);
      assertEquals("call_1", toolCall.getId());
      assertEquals("send_email", toolCall.getFunction().getName());
      assertEquals("{\"ssn\":\"<REDACTED>\"}", toolCall.getFunction().getArguments());
    }

    @Test
    void skipsToolCallPathWhenMessageHasNoToolCalls() {
      final List<Message> messages = messages(Message.message("user", "no tools here"));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              singletonList(
                  replacement("messages[0].tool_calls[0].function.arguments", "<REDACTED>")));

      assertFalse(result.redacted());
      assertEquals(1, result.skipped);
      assertSame(messages, result.messages);
    }

    @Test
    void skipsToolCallWhoseFunctionCarriesNoArguments() {
      final List<Message> messages =
          messages(
              Message.assistant(new ToolCall("call_1", new ToolCall.Function("send_email", null))));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              singletonList(
                  replacement("messages[0].tool_calls[0].function.arguments", "<REDACTED>")));

      assertFalse(result.redacted());
      assertEquals(1, result.skipped);
      assertSame(messages, result.messages);
    }

    @Test
    void redactsOnlyTheTargetedToolCall() {
      final List<Message> messages =
          messages(
              Message.assistant(
                  ToolCall.toolCall("call_1", "a", "{\"x\":1}"),
                  ToolCall.toolCall("call_2", "b", "{\"ssn\":\"123-45-6789\"}")));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              singletonList(
                  replacement(
                      "messages[0].tool_calls[1].function.arguments", "{\"ssn\":\"<REDACTED>\"}")));

      final List<ToolCall> toolCalls = result.messages.get(0).getToolCalls();
      assertEquals("{\"x\":1}", toolCalls.get(0).getFunction().getArguments());
      assertEquals("{\"ssn\":\"<REDACTED>\"}", toolCalls.get(1).getFunction().getArguments());
    }

    @Test
    void preservesContentPartsWhenRedactingToolCallOnTheSameMessage() {
      final Message message =
          new Message(
              "assistant",
              singletonList(ContentPart.text("calling a tool")),
              singletonList(ToolCall.toolCall("call_1", "send", "{\"ssn\":\"123-45-6789\"}")),
              null);

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages(message),
              singletonList(
                  replacement(
                      "messages[0].tool_calls[0].function.arguments", "{\"ssn\":\"<REDACTED>\"}")));

      final Message redacted = result.messages.get(0);
      assertNull(redacted.getContent());
      assertEquals("calling a tool", redacted.getContentParts().get(0).getText());
      assertEquals(
          "{\"ssn\":\"<REDACTED>\"}", redacted.getToolCalls().get(0).getFunction().getArguments());
    }
  }

  @Nested
  class MultipleReplacements {

    @Test
    void appliesReplacementsAcrossSeveralMessages() {
      final List<Message> messages =
          messages(
              Message.message("system", "contact ops@acme.io"),
              Message.message("user", "My SSN is 123-45-6789"),
              Message.message("assistant", "nothing sensitive here"));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              asList(
                  replacement("messages[0].content", "contact <REDACTED>"),
                  replacement("messages[1].content", "My SSN is <REDACTED>")));

      assertEquals(2, result.applied);
      assertEquals("contact <REDACTED>", result.messages.get(0).getContent());
      assertEquals("My SSN is <REDACTED>", result.messages.get(1).getContent());
      assertSame(messages.get(2), result.messages.get(2));
    }

    @Test
    void composesTwoReplacementsTargetingTheSameMessage() {
      final Message message =
          new Message(
              "assistant",
              asList(
                  ContentPart.text("card 4111111111111111"), ContentPart.text("ssn 123-45-6789")),
              singletonList(ToolCall.toolCall("call_1", "send", "{\"email\":\"a@b.io\"}")),
              null);

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages(message),
              asList(
                  replacement("messages[0].content[0].text", "card <REDACTED>"),
                  replacement("messages[0].content[1].text", "ssn <REDACTED>"),
                  replacement(
                      "messages[0].tool_calls[0].function.arguments",
                      "{\"email\":\"<REDACTED>\"}")));

      assertEquals(3, result.applied);
      assertEquals(0, result.skipped);
      final Message redacted = result.messages.get(0);
      assertEquals("card <REDACTED>", redacted.getContentParts().get(0).getText());
      assertEquals("ssn <REDACTED>", redacted.getContentParts().get(1).getText());
      assertEquals(
          "{\"email\":\"<REDACTED>\"}",
          redacted.getToolCalls().get(0).getFunction().getArguments());
    }

    @Test
    void appliesSurvivorsWhenOneEntryIsUnresolvable() {
      final List<Message> messages = messages(Message.message("user", "My SSN is 123-45-6789"));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              asList(
                  replacement("messages[0].content", "My SSN is <REDACTED>"),
                  replacement("messages[9].content", "never applied")));

      assertEquals(1, result.applied);
      assertEquals(1, result.skipped);
      assertEquals("My SSN is <REDACTED>", result.messages.get(0).getContent());
    }
  }

  @Nested
  class AppliedReplacements {

    @Test
    void reportsOnlyTheEntriesThatWereApplied() {
      final List<Message> messages = messages(Message.message("user", "My SSN is 123-45-6789"));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              asList(
                  replacement("messages[0].content", "My SSN is <REDACTED>"),
                  replacement("messages[9].content", "never applied")));

      assertEquals(1, result.applied);
      assertEquals(1, result.skipped);
    }

    @Test
    void keepsAnEmptyReplacementWhichMeansRemove() {
      final List<Message> messages = messages(Message.message("user", "SSN 123-45-6789"));

      final MessageRedactor.Result result =
          REDACTOR.redact(messages, singletonList(replacement("messages[0].content", "")));

      assertEquals(1, result.applied);
      assertEquals("", result.messages.get(0).getContent());
    }

    @Test
    void reportsNothingWhenNothingWasApplied() {
      final List<Message> messages = messages(Message.message("user", "hello"));

      assertEquals(0, REDACTOR.redact(messages, null).applied);
      assertEquals(0, new MessageRedactor.NoOp().redact(messages, null).applied);
    }
  }

  @Nested
  class MalformedResponses {

    @Test
    void returnsSameListWhenReplacementsAreNull() {
      final List<Message> messages = messages(Message.message("user", "hello"));

      final MessageRedactor.Result result = REDACTOR.redact(messages, null);

      assertSame(messages, result.messages);
      assertFalse(result.redacted());
      assertEquals(0, result.skipped);
    }

    @Test
    void returnsSameListWhenReplacementsAreEmpty() {
      final List<Message> messages = messages(Message.message("user", "hello"));

      final MessageRedactor.Result result = REDACTOR.redact(messages, Collections.emptyList());

      assertSame(messages, result.messages);
      assertFalse(result.redacted());
    }

    @Test
    void returnsSameListWhenThereAreNoMessages() {
      final MessageRedactor.Result nullMessages =
          REDACTOR.redact(null, singletonList(replacement("messages[0].content", "<REDACTED>")));
      assertNull(nullMessages.messages);
      assertFalse(nullMessages.redacted());

      final List<Message> empty = Collections.emptyList();
      final MessageRedactor.Result noMessages =
          REDACTOR.redact(empty, singletonList(replacement("messages[0].content", "<REDACTED>")));
      assertSame(empty, noMessages.messages);
      assertFalse(noMessages.redacted());
    }

    @Test
    void skipsEveryPathTheServiceContradictsItselfOn() {
      final List<Message> messages =
          messages(
              Message.message("system", "contact ops@acme.io"),
              Message.message("user", "My SSN is 123-45-6789"));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              asList(
                  replacement("messages[0].content", "first"),
                  replacement("messages[0].content", "second"),
                  replacement("messages[1].content", "third"),
                  replacement("messages[1].content", "fourth")));

      assertFalse(result.redacted());
      assertEquals(2, result.skipped);
      assertSame(messages, result.messages);
    }

    @Test
    void skipsConflictingReplacementsForTheSamePath() {
      final List<Message> messages = messages(Message.message("user", "My SSN is 123-45-6789"));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              asList(
                  replacement("messages[0].content", "first"),
                  replacement("messages[0].content", "second")));

      assertFalse(result.redacted());
      assertEquals(1, result.skipped);
      assertSame(messages, result.messages);
      assertEquals("My SSN is 123-45-6789", messages.get(0).getContent());
    }

    @Test
    void acceptsDuplicateReplacementsCarryingTheSameValue() {
      final List<Message> messages = messages(Message.message("user", "My SSN is 123-45-6789"));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              asList(
                  replacement("messages[0].content", "My SSN is <REDACTED>"),
                  replacement("messages[0].content", "My SSN is <REDACTED>")));

      assertEquals(1, result.applied);
      assertEquals(0, result.skipped);
      assertEquals("My SSN is <REDACTED>", result.messages.get(0).getContent());
    }

    @Test
    void skipsEntriesThatAreNotObjects() {
      final List<Message> messages = messages(Message.message("user", "hello"));

      final MessageRedactor.Result result = REDACTOR.redact(messages, asList("not an object", 42));

      assertFalse(result.redacted());
      assertEquals(2, result.skipped);
      assertSame(messages, result.messages);
    }

    @Test
    void skipsEntriesWithMissingOrNonStringFields() {
      final List<Message> messages = messages(Message.message("user", "hello"));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              asList(
                  replacement(null, "<REDACTED>"),
                  replacement("messages[0].content", null),
                  replacement("", "<REDACTED>"),
                  replacement(42, "<REDACTED>"),
                  replacement("messages[0].content", 42),
                  new HashMap<String, Object>()));

      assertFalse(result.redacted());
      assertEquals(6, result.skipped);
      assertSame(messages, result.messages);
    }

    @Test
    void neverThrowsOnAToolCallWithoutAFunction() {
      final List<Message> messages =
          messages(
              new Message(
                  "assistant", (String) null, singletonList(new ToolCall("call_1", null)), null));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages,
              singletonList(
                  replacement("messages[0].tool_calls[0].function.arguments", "<REDACTED>")));

      assertFalse(result.redacted());
      assertEquals(1, result.skipped);
    }

    @Test
    void skipsContentPathOnAMessageWithoutContent() {
      final List<Message> messages =
          messages(Message.assistant(ToolCall.toolCall("call_1", "send", "{}")));

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages, singletonList(replacement("messages[0].content", "<REDACTED>")));

      assertFalse(result.redacted());
      assertEquals(1, result.skipped);
    }
  }

  @Nested
  class PathGrammar {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "", // empty path
          "content", // not rooted at messages
          "messages", // resolves to the list itself
          "messages.content", // missing root index
          "messages[0]", // resolves to a message, not a string
          "messages[0].foo", // unknown field
          "messages[0].content.text", // missing part index
          "messages[0].content[0]", // resolves to a content part
          "messages[0].content[0].foo", // unknown content part field
          "messages[0].content[0].text.extra", // too deep
          "messages[0].content[1].image_url.url", // image locator, out of scope
          "messages[0].tool_calls.function.arguments", // missing tool call index
          "messages[0].tool_calls[0].function", // resolves to a function
          "messages[0].tool_calls[0].function.name", // not a redactable target
          "messages[0].tool_calls[0].arguments", // missing function hop
          "messages[-1].content", // negative index rejected by the segment regex
          "messages[0].content ", // trailing space, whole segment must match
          " messages[0].content", // leading space
          "messages[0]..content", // empty segment
          "messages[0].content[", // unbalanced bracket
          "messages[0].con-tent", // hyphen is not a word character
          "messages[99999999999].content", // index overflows
          "messages[0].tool_calls[0].function.arguments.extra", // deeper than any target
          "conversation[0].content", // rooted at something other than messages
          "messages[0].content[0].text[0]", // an index on the text field itself
          "messages[0].content[9].text", // content part index out of range
          "messages[0].tool_calls[0].foo.arguments", // the hop is not `function`
          "messages[0].tool_calls[0].function[0].arguments", // an index on `function`
          "messages[0].tool_calls[0].function.arguments[0]", // an index on `arguments`
          "messages[0].tool_calls[9].function.arguments" // tool call index out of range
        })
    void skipsUnsupportedPaths(final String path) {
      final List<Message> messages =
          messages(
              new Message(
                  "assistant",
                  asList(
                      ContentPart.text("text"), ContentPart.imageUrl("https://example.com/i.jpg")),
                  singletonList(ToolCall.toolCall("call_1", "send", "{}")),
                  null));

      final MessageRedactor.Result result =
          REDACTOR.redact(messages, singletonList(replacement(path, "<REDACTED>")));

      assertFalse(result.redacted(), "expected path to be skipped: " + path);
      assertEquals(1, result.skipped);
      assertSame(messages, result.messages);
    }

    @Test
    void acceptsMultiDigitIndexes() {
      final List<Message> messages = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        messages.add(Message.message("user", "message " + i));
      }

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages, singletonList(replacement("messages[11].content", "<REDACTED>")));

      assertTrue(result.redacted());
      assertEquals("<REDACTED>", result.messages.get(11).getContent());
    }

    @Test
    void toleratesUnknownKeysOnAReplacementEntry() {
      final Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("path", "messages[0].content");
      entry.put("replacement", "<REDACTED>");
      entry.put("category", "ssn");

      final MessageRedactor.Result result =
          REDACTOR.redact(
              messages(Message.message("user", "My SSN is 123-45-6789")), singletonList(entry));

      assertTrue(result.redacted());
      assertEquals("<REDACTED>", result.messages.get(0).getContent());
    }
  }
}
