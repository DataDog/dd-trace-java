package datadog.trace.api.appsec;

import java.util.Locale;

public class MediaType {

  public static final MediaType UNKNOWN = new MediaType("application", "octet-stream", null);

  private final String type;
  private final String subtype;
  private final String charset;

  private MediaType(final String type, final String subtype, final String charset) {
    this.type = type;
    this.subtype = subtype;
    this.charset = charset;
  }

  public String getType() {
    return type;
  }

  public String getSubtype() {
    return subtype;
  }

  public String getCharset() {
    return charset;
  }

  public boolean isJson() {
    return subtype != null && ("json".equals(subtype) || subtype.endsWith("+json"));
  }

  public boolean isDeserializable() {
    // TODO add other supported types
    return isJson();
  }

  @Override
  public String toString() {
    String contentType = type + "/" + subtype;
    if (charset != null) {
      contentType += "; charset=" + charset;
    }
    return contentType;
  }

  public static MediaType parse(final String header) {
    if (header == null) {
      return UNKNOWN;
    }
    final String mediaType = header.trim().toLowerCase(Locale.ROOT);
    int semicolonIndex = mediaType.indexOf(';');
    String contentType, charset = null;
    if (semicolonIndex != -1) {
      contentType = mediaType.substring(0, semicolonIndex);
      String parameter = mediaType.substring(semicolonIndex + 1);
      final int charsetIndex = parameter.indexOf("charset=");
      if (charsetIndex != -1) {
        charset = parameter.substring(charsetIndex + 8).trim();
      }
    } else {
      contentType = mediaType;
    }
    int slashIndex = contentType.indexOf('/');
    if (slashIndex != -1) {
      final String type = contentType.substring(0, slashIndex).trim();
      final String subtype = contentType.substring(slashIndex + 1).trim();
      return new MediaType(type, subtype, charset);
    } else {
      return new MediaType(contentType, null, charset);
    }
  }

  public static MediaType create(final String type, final String subtype, final String charset) {
    return new MediaType(type, subtype, charset);
  }
}
