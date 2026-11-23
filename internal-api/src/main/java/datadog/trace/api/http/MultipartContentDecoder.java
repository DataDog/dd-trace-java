package datadog.trace.api.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;

/** Decodes multipart file content bytes to String using the per-part Content-Type charset. */
public final class MultipartContentDecoder {

  public static String readInputStream(InputStream is, int maxBytes, String contentType)
      throws IOException {
    byte[] buf = new byte[maxBytes];
    int total = 0;
    int n;
    while (total < maxBytes && (n = is.read(buf, total, maxBytes - total)) != -1) {
      total += n;
    }
    return decodeBytes(buf, total, contentType);
  }

  public static String decodeBytes(byte[] buf, int length, String contentType) {
    Charset charset = extractCharset(contentType);
    if (charset == null) charset = Charset.defaultCharset();
    try {
      return charset
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPLACE)
          .onUnmappableCharacter(CodingErrorAction.REPLACE)
          .decode(ByteBuffer.wrap(buf, 0, length))
          .toString();
    } catch (CharacterCodingException e) {
      // unreachable: CodingErrorAction.REPLACE never throws CharacterCodingException
      throw new IllegalStateException(e);
    }
  }

  /**
   * Walks the media-type parameter list of a Content-Type value looking for a {@code charset}
   * parameter, honoring RFC 7230 quoted-string syntax for parameter values. Quoted values are fully
   * consumed as opaque tokens, so a {@code charset}-looking substring inside another parameter's
   * quoted value (e.g. {@code boundary="charset=oops"}) can never be mistaken for a real {@code
   * charset} parameter. If a {@code charset} parameter is found but its value is not a valid
   * charset name, the search continues to any later {@code charset} parameter instead of giving up.
   */
  public static Charset extractCharset(String contentType) {
    if (contentType == null) return null;
    int len = contentType.length();
    // Skip the media type itself (e.g. "text/plain") up to the first parameter delimiter.
    int i = skipToDelimiter(contentType, 0, len);
    while (i < len) {
      i++; // consume the ';' or ',' that ends the previous token
      i = skipOws(contentType, i, len);
      int nameStart = i;
      while (i < len
          && contentType.charAt(i) != '='
          && contentType.charAt(i) != ';'
          && contentType.charAt(i) != ',') {
        i++;
      }
      if (i >= len || contentType.charAt(i) != '=') {
        // Parameter with no '=' (malformed) — skip past it and keep looking.
        i = skipToDelimiter(contentType, i, len);
        continue;
      }
      int nameEnd = i;
      while (nameEnd > nameStart && isOws(contentType.charAt(nameEnd - 1))) nameEnd--;
      String name = contentType.substring(nameStart, nameEnd);
      i = skipOws(contentType, i + 1, len);
      String value;
      if (i < len && contentType.charAt(i) == '"') {
        StringBuilder sb = new StringBuilder();
        i++;
        while (i < len && contentType.charAt(i) != '"') {
          char c = contentType.charAt(i);
          if (c == '\\' && i + 1 < len) {
            i++;
            c = contentType.charAt(i);
          }
          sb.append(c);
          i++;
        }
        if (i < len) i++; // consume closing quote
        value = sb.toString();
        i = skipToDelimiter(contentType, i, len);
      } else {
        int valStart = i;
        i = skipToDelimiter(contentType, i, len);
        int valEnd = i;
        while (valEnd > valStart && isOws(contentType.charAt(valEnd - 1))) valEnd--;
        value = contentType.substring(valStart, valEnd);
      }
      if (name.equalsIgnoreCase("charset")) {
        try {
          return Charset.forName(value);
        } catch (IllegalArgumentException ignored) {
          // An invalid charset value here does not rule out a later, valid charset parameter.
        }
      }
    }
    return null;
  }

  private static int skipToDelimiter(String s, int i, int len) {
    while (i < len && s.charAt(i) != ';' && s.charAt(i) != ',') i++;
    return i;
  }

  private static boolean isOws(char c) {
    return c == ' ' || c == '\t';
  }

  private static int skipOws(String s, int i, int len) {
    while (i < len && isOws(s.charAt(i))) i++;
    return i;
  }

  private MultipartContentDecoder() {}
}
