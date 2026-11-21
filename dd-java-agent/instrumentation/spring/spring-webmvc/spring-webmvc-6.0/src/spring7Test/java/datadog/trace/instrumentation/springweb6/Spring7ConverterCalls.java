package datadog.trace.instrumentation.springweb6;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring 7 calls exercised by {@link AppSecSmartHttpMessageConverterTest}.
 *
 * <p>Kept out of the test class so that Spring classes are not loaded (by bytecode verification)
 * when JUnit discovers the test class, which happens before the agent is installed: classes loaded
 * that early are never transformed.
 */
final class Spring7ConverterCalls {

  private Spring7ConverterCalls() {}

  /** Legacy {@code read(Class, HttpInputMessage)} on the Jackson 3 converter. */
  static Object classRead(String json) throws Exception {
    return new JacksonJsonHttpMessageConverter().read(Map.class, jsonInput(json));
  }

  /** Smart {@code read(ResolvableType, HttpInputMessage, Map)} on the Jackson 3 converter. */
  static Object smartRead(String json) throws Exception {
    return new JacksonJsonHttpMessageConverter()
        .read(ResolvableType.forClass(Map.class), jsonInput(json), null);
  }

  /** Smart read of a JSON string into a {@code String} target on the Jackson 3 converter. */
  static Object smartReadString(String json) throws Exception {
    return new JacksonJsonHttpMessageConverter()
        .read(ResolvableType.forClass(String.class), jsonInput(json), null);
  }

  /**
   * Smart {@code write(Object, ResolvableType, MediaType, HttpOutputMessage, Map)} on the Jackson 3
   * converter.
   *
   * @return the written body
   */
  static String smartWrite(Map<String, Object> body) throws Exception {
    MockHttpOutputMessage output = new MockHttpOutputMessage();
    new JacksonJsonHttpMessageConverter()
        .write(body, ResolvableType.forClass(Map.class), MediaType.APPLICATION_JSON, output, null);
    return output.getBodyAsString(StandardCharsets.UTF_8);
  }

  /**
   * POSTs the JSON through a standalone MockMvc whose only converter is the Jackson 3 one, so the
   * body goes through the real argument resolver call site.
   *
   * @return the body bound to the {@code @RequestBody Map} controller parameter
   */
  static Map<String, Object> mockMvcPost(String json) throws Exception {
    BodyController controller = new BodyController();
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new JacksonJsonHttpMessageConverter())
            .build();
    mockMvc
        .perform(post("/body").contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isOk());
    return controller.received;
  }

  private static MockHttpInputMessage jsonInput(String json) {
    MockHttpInputMessage message = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
    message.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    return message;
  }

  /** Controller binding a JSON body to a {@code Map} through the argument resolver. */
  @RestController
  static class BodyController {
    Map<String, Object> received;

    @PostMapping("/body")
    void body(@RequestBody Map<String, Object> body) {
      received = body;
    }
  }
}
