package datadog.trace.api.http;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Locale;

/** Decodes multipart file content bytes to String using the per-part Content-Type charset. */
public final class MultipartContentDecoder {

  public static String decodeBytes(byte[] buf, int length, String contentType) {
    Charset charset = extractCharset(contentType);
    if (charset == null) charset = Charset.defaultCharset();
    try {
      return charset
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(buf, 0, length))
          .toString();
    } catch (CharacterCodingException e) {
      return new String(buf, 0, length, Charset.defaultCharset());
    }
  }

  public static Charset extractCharset(String contentType) {
    if (contentType == null) return null;
    int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
    if (idx < 0) return null;
    int nameStart = idx + 8;
    int end = contentType.length();
    for (int i = nameStart; i < contentType.length(); i++) {
      char c = contentType.charAt(i);
      if (c == ';' || c == ',' || c == ' ') {
        end = i;
        break;
      }
    }
    String name = contentType.substring(nameStart, end).trim();
    if (name.length() > 1 && name.charAt(0) == '"' && name.charAt(name.length() - 1) == '"') {
      name = name.substring(1, name.length() - 1);
    }
    try {
      return Charset.forName(name);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private MultipartContentDecoder() {}
}
