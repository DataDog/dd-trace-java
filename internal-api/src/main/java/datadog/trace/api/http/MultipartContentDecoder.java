package datadog.trace.api.http;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Decodes multipart file content bytes to String using the per-part Content-Type charset. */
public final class MultipartContentDecoder {

  public static String decodeBytes(byte[] buf, int length, String contentType) {
    Charset charset = extractCharset(contentType);
    if (charset == null) charset = StandardCharsets.UTF_8;
    try {
      return charset
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(buf, 0, length))
          .toString();
    } catch (CharacterCodingException e) {
      return new String(buf, 0, length, StandardCharsets.ISO_8859_1);
    }
  }

  public static Charset extractCharset(String contentType) {
    if (contentType == null) return null;
    int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
    if (idx < 0) return null;
    String name = contentType.substring(idx + 8).split("[;, ]")[0].trim();
    try {
      return Charset.forName(name);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private MultipartContentDecoder() {}
}
