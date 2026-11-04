package datadog.trace.bootstrap.blocking;

import static datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType.HTML;
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType.JSON;
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.determineTemplateType;
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.getContentType;
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.getHttpCode;
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.getTemplate;
import static datadog.trace.bootstrap.blocking.BlockingActionHelper.reset;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.blocking.BlockingActionHelper.TemplateType;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class BlockingActionHelperTest {

  private static final String SECURITY_RESPONSE_ID_PLACEHOLDER = "[security_response_id]";

  @TempDir File tempDir;

  @TableTest({
    "scenario                    | accept                                                                | templateType",
    "null                        |                                                                       | JSON        ",
    "empty                       | ''                                                                    | JSON        ",
    "wildcard                    | '*/*'                                                                 | JSON        ",
    "html                        | text/html                                                             | HTML        ",
    "html utf-8                  | 'text/html;charset=utf-8'                                             | HTML        ",
    "html iso-8859-1             | 'text/html;charset=iso-8859-1'                                        | HTML        ",
    "json trailing comma         | 'application/json, '                                                  | JSON        ",
    "same quality                | 'application/json; q=0.9,text/html; q=0.9'                            | JSON        ",
    "html higher quality         | 'application/json; q=0.9,text/html; q=0.91'                           | HTML        ",
    "text wildcard higher        | 'application/json;q=0.7, application/*;q=0.9, text/*;q=0.8'           | HTML        ",
    "json more specific          | 'application/*;q=0.6, application/json;q=0.81 text/*;level=2; q=0.8,' | JSON        ",
    "text wildcard over wildcard | '*/*;q=0.8, text/*;q=0.9'                                             | HTML        ",
    "html lower quality          | '*/*;q=0.8, text/*;q=0.9, text/html ; q=0.7'                          | JSON        ",
    "gives up after error        | 'aaaa,text/html'                                                      | JSON        "
  })
  void determineTemplateWithAuto(String accept, TemplateType templateType) {
    assertEquals(templateType, determineTemplateType(BlockingContentType.AUTO, accept));
  }

  @Test
  void determineTemplateWithJson() {
    assertEquals(JSON, determineTemplateType(BlockingContentType.JSON, "text/html"));
  }

  @Test
  void determineTemplateWithHtml() {
    assertEquals(HTML, determineTemplateType(BlockingContentType.HTML, "application/json"));
  }

  @TableTest({
    "scenario  | input | result",
    "zero      | 0     | 403   ",
    "below 200 | 199   | 403   ",
    "above 599 | 600   | 403   ",
    "ok        | 200   | 200   ",
    "not found | 404   | 404   "
  })
  void getHttpCodeReturnsResult(int input, int result) {
    assertEquals(result, getHttpCode(input));
  }

  @TableTest({
    "scenario | input | result                   ",
    "json     | JSON  | application/json         ",
    "html     | HTML  | 'text/html;charset=utf-8'",
    "null     |       |                          "
  })
  void getContentTypeReturnsResult(TemplateType input, String result) {
    assertEquals(result, getContentType(input));
  }

  // JSON value is unquoted (TableTest has no escape for a cell holding both quote kinds);
  // the leading ':' keeps it from being parsed as a double-quoted cell
  @TableTest({
    "scenario | templateType | expectedContent                   ",
    "html     | HTML         | <title>You've been blocked</title>",
    "json     | JSON         | :\"You've been blocked\"          "
  })
  void getTemplateReturningDefaultTemplate(TemplateType templateType, String expectedContent) {
    assertTrue(templateAsString(templateType).contains(expectedContent));
  }

  @TableTest({
    "scenario | templateType | fileName      | templateContent           ",
    "html     | HTML         | template.html | '<body>My template</body>'",
    "json     | JSON         | template.json | '{\"foo\":\"bar\"}'       "
  })
  void getTemplateReturningCustomTemplate(
      TemplateType templateType, String fileName, String templateContent) throws IOException {
    Config config = mock(Config.class);
    File tempFile = new File(tempDir, fileName);
    writeFile(tempFile, templateContent.getBytes(UTF_8));
    when(config.getAppSecHttpBlockedTemplateHtml())
        .thenReturn(templateType == HTML ? tempFile.toString() : null);
    when(config.getAppSecHttpBlockedTemplateJson())
        .thenReturn(templateType == JSON ? tempFile.toString() : null);

    try {
      // when
      reset(config);

      // then
      verify(config, times(1)).getAppSecHttpBlockedTemplateHtml();
      verify(config, times(1)).getAppSecHttpBlockedTemplateJson();
      assertTrue(templateAsString(templateType).contains(templateContent));
    } finally {
      // cleanup
      reset(Config.get());
    }
  }

  @Test
  void getTemplateWithNullArgument() {
    assertNull(getTemplate(null));
  }

  @TableTest({
    "scenario                        | templateType | tooBigFile | expectedContent                   ",
    "html custom file does not exist | HTML         | false      | <title>You've been blocked</title>",
    "json custom file does not exist | JSON         | false      | \"You've been blocked             ",
    "html custom file is too big     | HTML         | true       | <title>You've been blocked</title>",
    "json custom file is too big     | JSON         | true       | \"You've been blocked             "
  })
  void willUseDefaultTemplate(TemplateType templateType, boolean tooBigFile, String expectedContent)
      throws IOException {
    Config config = mock(Config.class);
    String htmlConfigValue = "/bad/file.html";
    String jsonConfigValue = "/bad/file.json";
    if (tooBigFile) {
      File template = new File(tempDir, "template");
      byte[] content = new byte[500 * 1024 + 1];
      Arrays.fill(content, (byte) 'a');
      writeFile(template, content);
      htmlConfigValue = template.toString();
      jsonConfigValue = template.toString();
    }
    when(config.getAppSecHttpBlockedTemplateHtml()).thenReturn(htmlConfigValue);
    when(config.getAppSecHttpBlockedTemplateJson()).thenReturn(jsonConfigValue);

    try {
      // when
      reset(config);

      // then
      verify(config, times(1)).getAppSecHttpBlockedTemplateHtml();
      verify(config, times(1)).getAppSecHttpBlockedTemplateJson();
      assertTrue(templateAsString(templateType).contains(expectedContent));
    } finally {
      // cleanup
      reset(Config.get());
    }
  }

  @TableTest({
    "scenario | templateType | expectedContent                    ",
    "html     | HTML         | 'Security Response ID: [id]'       ",
    "json     | JSON         | '\"security_response_id\":\"[id]\"'"
  })
  void getTemplateWithSecurityResponseIdReplacesPlaceholder(
      TemplateType templateType, String expectedContent) {
    // given
    String securityResponseId = "12345678-1234-1234-1234-123456789abc";

    // when
    String templateStr = templateAsString(templateType, securityResponseId);

    // then
    assertFalse(templateStr.contains(SECURITY_RESPONSE_ID_PLACEHOLDER));
    assertTrue(templateStr.contains(expectedContent.replace("[id]", securityResponseId)));
  }

  @TableTest({
    "scenario | templateType | expectedContents                    ",
    "html     | HTML         | ['Security Response ID:']           ",
    "json     | JSON         | ['\"security_response_id\"', '\"\"']"
  })
  void getTemplateWithoutSecurityResponseIdUsesEmptyString(
      TemplateType templateType, List<String> expectedContents) {
    // when
    String templateStr = templateAsString(templateType, null);

    // then
    assertFalse(templateStr.contains(SECURITY_RESPONSE_ID_PLACEHOLDER));
    for (String content : expectedContents) {
      assertTrue(templateStr.contains(content), content);
    }
  }

  @Test
  void getTemplateWithEmptySecurityResponseIdUsesEmptyString() {
    // when
    String htmlTemplate = templateAsString(HTML, "");
    String jsonTemplate = templateAsString(JSON, "");

    // then
    assertFalse(htmlTemplate.contains(SECURITY_RESPONSE_ID_PLACEHOLDER));
    assertFalse(jsonTemplate.contains(SECURITY_RESPONSE_ID_PLACEHOLDER));
  }

  @TableTest({
    "scenario | templateType | fileName      | templateContent                                                                  | expectedContent                                  ",
    "html     | HTML         | template.html | '<body>Custom template with security_response_id: [security_response_id]</body>' | 'Custom template with security_response_id: [id]'",
    "json     | JSON         | template.json | '{\"error\":\"blocked\",\"id\":\"[security_response_id]\"}'                      | '\"error\":\"blocked\",\"id\":\"[id]\"'          "
  })
  void getTemplateWithSecurityResponseIdWorksWithCustomTemplate(
      TemplateType templateType, String fileName, String templateContent, String expectedContent)
      throws IOException {
    Config config = mock(Config.class);
    File tempFile = new File(tempDir, fileName);
    writeFile(tempFile, templateContent.getBytes(UTF_8));
    String securityResponseId = "test-block-id-123";
    when(config.getAppSecHttpBlockedTemplateHtml())
        .thenReturn(templateType == HTML ? tempFile.toString() : null);
    when(config.getAppSecHttpBlockedTemplateJson())
        .thenReturn(templateType == JSON ? tempFile.toString() : null);

    try {
      // when
      reset(config);
      String templateStr = templateAsString(templateType, securityResponseId);

      // then
      verify(config, times(1)).getAppSecHttpBlockedTemplateHtml();
      verify(config, times(1)).getAppSecHttpBlockedTemplateJson();
      assertTrue(templateStr.contains(expectedContent.replace("[id]", securityResponseId)));
      assertFalse(templateStr.contains(SECURITY_RESPONSE_ID_PLACEHOLDER));
    } finally {
      // cleanup
      reset(Config.get());
    }
  }

  private static String templateAsString(TemplateType templateType) {
    return new String(getTemplate(templateType), UTF_8);
  }

  private static String templateAsString(TemplateType templateType, String securityResponseId) {
    return new String(getTemplate(templateType, securityResponseId), UTF_8);
  }

  private static void writeFile(File file, byte[] content) throws IOException {
    try (OutputStream out = new FileOutputStream(file)) {
      out.write(content);
    }
  }
}
