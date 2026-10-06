package com.datadog.profiling.otel.proto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Checks every {@link OtlpProtoFields} constant against the field numbers in the
 * opentelemetry-proto files copied into the test resources. When bumping the pinned proto version,
 * replace those files with the ones from the new version.
 */
class OtlpProtoFieldsTest {

  private static final String[] PROTO_FILES = {
    "/opentelemetry/proto/profiles/v1development/profiles.proto",
    "/opentelemetry/proto/common/v1/common.proto",
    "/opentelemetry/proto/resource/v1/resource.proto"
  };

  // top-level messages only: the closing brace of a message is at column 0
  private static final Pattern MESSAGE =
      Pattern.compile("^message (\\w+) \\{(.*?)^\\}", Pattern.MULTILINE | Pattern.DOTALL);
  private static final Pattern FIELD = Pattern.compile("(\\w+)\\s*=\\s*(\\d+)\\s*;");
  private static final Pattern COMMENT = Pattern.compile("//.*");

  @Test
  void fieldNumbersMatchProtoDefinitions() throws Exception {
    Map<String, Map<String, Integer>> messages = parseMessages();
    List<String> mismatches = new ArrayList<>();
    for (Class<?> message : OtlpProtoFields.class.getDeclaredClasses()) {
      Map<String, Integer> fields = messages.get(message.getSimpleName());
      for (Field constant : message.getDeclaredFields()) {
        if (!Modifier.isStatic(constant.getModifiers()) || constant.getType() != int.class) {
          continue;
        }
        Integer expected = fields == null ? null : fields.get(constant.getName());
        int actual = constant.getInt(null);
        if (expected == null || expected != actual) {
          mismatches.add(
              message.getSimpleName()
                  + "."
                  + constant.getName()
                  + " = "
                  + actual
                  + ", proto: "
                  + expected);
        }
      }
    }
    assertEquals(new ArrayList<String>(), mismatches);
  }

  private static Map<String, Map<String, Integer>> parseMessages() throws IOException {
    Map<String, Map<String, Integer>> messages = new HashMap<>();
    for (String file : PROTO_FILES) {
      Matcher message = MESSAGE.matcher(read(file));
      while (message.find()) {
        Map<String, Integer> fields =
            messages.computeIfAbsent(message.group(1), k -> new HashMap<>());
        Matcher field = FIELD.matcher(COMMENT.matcher(message.group(2)).replaceAll(""));
        while (field.find()) {
          fields.put(field.group(1).toUpperCase(Locale.ROOT), Integer.parseInt(field.group(2)));
        }
      }
    }
    return messages;
  }

  private static String read(String resource) throws IOException {
    try (InputStream in = OtlpProtoFieldsTest.class.getResourceAsStream(resource)) {
      assertNotNull(in, resource);
      byte[] buffer = new byte[8192];
      StringBuilder content = new StringBuilder();
      int read;
      while ((read = in.read(buffer)) != -1) {
        content.append(new String(buffer, 0, read, UTF_8));
      }
      return content.toString();
    }
  }
}
