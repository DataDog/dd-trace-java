package datadog.trace.api.aiguard;

import static datadog.trace.api.aiguard.AIGuard.Action.ALLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class AIGuardTest {

  @Test
  void testTextMessage() {
    AIGuard.Message message = AIGuard.Message.message("user", "What day is today?");

    assertEquals("user", message.getRole());
    assertEquals("What day is today?", message.getContent());
    assertNull(message.getToolCallId());
    assertNull(message.getToolCalls());
  }

  @Test
  void testAssistantToolCall() {
    AIGuard.Message message =
        AIGuard.Message.assistant(
            AIGuard.ToolCall.toolCall(
                "1", "execute_http_request", "{ \"url\": \"http://localhost\" }"),
            AIGuard.ToolCall.toolCall("2", "random_number", "{ \"min\": 0, \"max\": 10 }"));

    assertEquals("assistant", message.getRole());
    assertNull(message.getContent());
    assertNull(message.getToolCallId());
    assertNotNull(message.getToolCalls());
    assertEquals(2, message.getToolCalls().size());

    AIGuard.ToolCall http = message.getToolCalls().get(0);
    assertEquals("1", http.getId());
    assertEquals("execute_http_request", http.getFunction().getName());
    assertEquals("{ \"url\": \"http://localhost\" }", http.getFunction().getArguments());

    AIGuard.ToolCall random = message.getToolCalls().get(1);
    assertEquals("2", random.getId());
    assertEquals("random_number", random.getFunction().getName());
    assertEquals("{ \"min\": 0, \"max\": 10 }", random.getFunction().getArguments());
  }

  @Test
  void testTool() {
    AIGuard.Message message = AIGuard.Message.tool("2", "5");

    assertEquals("tool", message.getRole());
    assertEquals("5", message.getContent());
    assertEquals("2", message.getToolCallId());
    assertNull(message.getToolCalls());
  }

  @Test
  void testNoopImplementation() {
    List<AIGuard.Message> messages =
        Arrays.asList(
            AIGuard.Message.message("system", "You are a beautiful AI assistant"),
            AIGuard.Message.message("user", "What day is today?"),
            AIGuard.Message.message("assistant", "Today is monday"),
            AIGuard.Message.message("user", "Give me a random number"),
            AIGuard.Message.assistant(
                AIGuard.ToolCall.toolCall(
                    "1", "generate_random_number", "{ \"min\": 0, \"max\": 10 }")),
            AIGuard.Message.tool("1", "5"),
            AIGuard.Message.message("assistant", "Your number is 5"));

    AIGuard.Evaluation evaluation = AIGuard.evaluate(messages);

    assertEquals(ALLOW, evaluation.getAction());
    assertEquals("AI Guard is not enabled", evaluation.getReason());
  }

  @Test
  void testContentPartTextFactory() {
    AIGuard.ContentPart part = AIGuard.ContentPart.text("Hello world");

    assertEquals(AIGuard.ContentPart.Type.TEXT, part.getType());
    assertEquals("Hello world", part.getText());
    assertNull(part.getImageUrl());
  }

  @Test
  void testContentPartImageUrlFromStringFactory() {
    AIGuard.ContentPart part = AIGuard.ContentPart.imageUrl("https://example.com/image.jpg");

    assertEquals(AIGuard.ContentPart.Type.IMAGE_URL, part.getType());
    assertNull(part.getText());
    assertNotNull(part.getImageUrl());
    assertEquals("https://example.com/image.jpg", part.getImageUrl().getUrl());
  }

  @Test
  void testMessageWithContentParts() {
    AIGuard.Message message =
        AIGuard.Message.message(
            "user",
            Arrays.asList(
                AIGuard.ContentPart.text("Describe this image:"),
                AIGuard.ContentPart.imageUrl("https://example.com/image.jpg")));

    assertEquals("user", message.getRole());
    assertNull(message.getContent());
    assertNotNull(message.getContentParts());
    assertEquals(2, message.getContentParts().size());
    assertEquals(AIGuard.ContentPart.Type.TEXT, message.getContentParts().get(0).getType());
    assertEquals("Describe this image:", message.getContentParts().get(0).getText());
    assertEquals(AIGuard.ContentPart.Type.IMAGE_URL, message.getContentParts().get(1).getType());
    assertEquals(
        "https://example.com/image.jpg", message.getContentParts().get(1).getImageUrl().getUrl());
  }

  @Test
  void testMessageWithPlainContentReturnsNullContentParts() {
    AIGuard.Message message = AIGuard.Message.message("user", "Hello");

    assertEquals("Hello", message.getContent());
    assertNull(message.getContentParts());
  }

  @Test
  void testMessageWithContentPartsReturnsNullContent() {
    AIGuard.Message message =
        AIGuard.Message.message(
            "user", Collections.singletonList(AIGuard.ContentPart.text("Hello")));

    assertNull(message.getContent());
    assertNotNull(message.getContentParts());
  }

  @Test
  void testMessageValidationAllowsNullContentForAssistantWithToolCalls() {
    AIGuard.Message message =
        AIGuard.Message.assistant(AIGuard.ToolCall.toolCall("1", "test", "{}"));

    assertEquals("assistant", message.getRole());
    assertNull(message.getContent());
    assertNull(message.getContentParts());
    assertNotNull(message.getToolCalls());
  }

  @Test
  void testMessageAllowsEmptyContentPartsList() {
    AIGuard.Message message =
        new AIGuard.Message("user", Collections.<AIGuard.ContentPart>emptyList(), null, null);

    assertNotNull(message.getContentParts());
    assertTrue(message.getContentParts().isEmpty());
  }
}
