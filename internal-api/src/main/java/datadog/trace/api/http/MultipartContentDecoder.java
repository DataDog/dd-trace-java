package datadog.trace.api.http;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;

/** Decodes multipart file content bytes to String using the per-part Content-Type charset. */
public final class MultipartContentDecoder {

  public static String decodeBytes(byte[] buf, int length, String contentType) {
    Charset charset = extractCharset(contentType);
    if (charset == null) charset = Charset.defaultCharset();
    return charset
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(ByteBuffer.wrap(buf, 0, length))
        .toString();
  }

  public static Charset extractCharset(String contentType) {
    if (contentType == null) return null;
    int searchFrom = 0;
    while (true) {
      int idx = indexOfIgnoreAsciiCase(contentType, "charset=", searchFrom);
      if (idx < 0) return null;
      // Require a parameter boundary before "charset=" so "xcharset=..." is not matched
      if (idx == 0 || contentType.charAt(idx - 1) == ';' || contentType.charAt(idx - 1) == ' ') {
        int nameStart = idx + 8;
        int end = contentType.length();
        for (int i = nameStart; i < end; i++) {
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
      searchFrom = idx + 1;
    }
  }

  private static int indexOfIgnoreAsciiCase(String s, String needle, int fromIndex) {
    int sLen = s.length();
    int nLen = needle.length();
    outer:
    for (int i = fromIndex, max = sLen - nLen; i <= max; i++) {
      for (int j = 0; j < nLen; j++) {
        if (Character.toLowerCase(s.charAt(i + j)) != needle.charAt(j)) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private MultipartContentDecoder() {}
}
