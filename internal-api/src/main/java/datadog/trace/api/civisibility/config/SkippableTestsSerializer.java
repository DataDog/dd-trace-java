package datadog.trace.api.civisibility.config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class SkippableTestsSerializer {

  private static final Logger LOGGER = LoggerFactory.getLogger(SkippableTestsSerializer.class);

  private static final Charset CHARSET = StandardCharsets.UTF_8;

  static final char FIELD_DELIMITER = ':';
  static final char RECORD_DELIMITER = ',';
  static final char ESCAPE_CHARACTER = '\\';

  public static String serialize(Collection<SkippableTest> skippableTests) {
    if (skippableTests == null || skippableTests.isEmpty()) {
      return "";
    }
    String uncompressed =
        skippableTests.stream()
            .map(SkippableTestsSerializer::serialize)
            .collect(Collectors.joining(String.valueOf(RECORD_DELIMITER)));
    byte[] compressed = gzip(uncompressed.getBytes(StandardCharsets.UTF_8));
    return Base64.getEncoder().encodeToString(compressed);
  }

  private static String serialize(SkippableTest test) {
    String suite = test.getSuite();
    String name = test.getName();
    String parameters = test.getParameters();

    int expectedLength =
        suite.length() + name.length() + (parameters != null ? parameters.length() : 0);
    StringBuilder sb = new StringBuilder(expectedLength);
    appendEscaped(suite, sb);
    sb.append(FIELD_DELIMITER);
    appendEscaped(name, sb);
    if (parameters != null && !parameters.isEmpty()) {
      sb.append(FIELD_DELIMITER);
      appendEscaped(parameters, sb);
    }
    return sb.toString();
  }

  private static void appendEscaped(String s, StringBuilder sb) {
    if (s == null || s.isEmpty()) {
      return;
    }
    int l = s.length();
    for (int i = 0; i < l; ) {
      int codepoint = s.codePointAt(i);
      switch (codepoint) {
        case FIELD_DELIMITER:
        case RECORD_DELIMITER:
        case ESCAPE_CHARACTER:
          sb.append(ESCAPE_CHARACTER).appendCodePoint(codepoint);
          break;
        default:
          sb.appendCodePoint(codepoint);
          break;
      }
      i += Character.charCount(codepoint);
    }
  }

  public static Collection<SkippableTest> deserialize(String s) {
    if (s == null || s.isEmpty()) {
      return Collections.emptyList();
    }
    byte[] compressed = Base64.getDecoder().decode(s);
    String uncompressed = new String(gunzip(compressed), CHARSET);
    return deserializeDecompressed(uncompressed);
  }

  private static Collection<SkippableTest> deserializeDecompressed(String s) {
    if (s == null || s.isEmpty()) {
      return Collections.emptyList();
    }

    Collection<SkippableTest> tests = new ArrayList<>();

    String[] tokens = new String[3];
    int tokenIdx = 0;

    StringBuilder sb = new StringBuilder();
    int l = s.length();
    for (int i = 0; i < l; ) {
      int codepoint = s.codePointAt(i);
      i += Character.charCount(codepoint);

      switch (codepoint) {
        case ESCAPE_CHARACTER:
          int nextCodepoint = s.codePointAt(i);
          if (nextCodepoint == ESCAPE_CHARACTER
              || nextCodepoint == FIELD_DELIMITER
              || nextCodepoint == RECORD_DELIMITER) {
            sb.appendCodePoint(nextCodepoint);
            i += Character.charCount(nextCodepoint);
          } else {
            sb.appendCodePoint(codepoint);
          }
          break;
        case FIELD_DELIMITER:
          tokens[tokenIdx++] = sb.toString();
          sb.setLength(0);
          break;
        case RECORD_DELIMITER:
          tokens[tokenIdx] = sb.toString();
          tests.add(new SkippableTest(tokens[0], tokens[1], tokens[2], null));
          sb.setLength(0);
          tokens[tokenIdx] = null;
          tokenIdx = 0;
          break;
        default:
          sb.appendCodePoint(codepoint);
          break;
      }
    }

    tokens[tokenIdx] = sb.toString();
    tests.add(new SkippableTest(tokens[0], tokens[1], tokens[2], null));

    return tests;
  }

  private static byte[] gzip(byte[] bytes) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream(bytes.length);
    try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(baos)) {
      gzipOutputStream.write(bytes, 0, bytes.length);
    } catch (IOException e) {
      LOGGER.error("Error while compressing skippable tests data", e);
      return new byte[0];
    }
    return baos.toByteArray();
  }

  private static byte[] gunzip(byte[] bytes) {
    byte[] buffer = new byte[4096];
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      int read;
      while ((read = gzipInputStream.read(buffer)) != -1) {
        baos.write(buffer, 0, read);
      }
    } catch (IOException e) {
      LOGGER.error("Error while decompressing skippable tests data", e);
      return new byte[0];
    }
    return baos.toByteArray();
  }
}
