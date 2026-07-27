package datadog.trace.instrumentation.resteasy;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.http.MultipartContentDecoder;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MultipartHelper {

  private static final Logger log = LoggerFactory.getLogger(MultipartHelper.class);

  public static final int MAX_CONTENT_BYTES = Config.get().getAppSecMaxFileContentBytes();
  public static final int MAX_FILES_TO_INSPECT = Config.get().getAppSecMaxFileContentCount();

  private MultipartHelper() {}

  // Reflection avoids a bytecode ref to MultivaluedMap (javax→jakarta in RESTEasy 6)
  private static final Method GET_HEADERS;

  static {
    Method m = null;
    try {
      m = InputPart.class.getMethod("getHeaders");
    } catch (NoSuchMethodException ignored) {
    }
    GET_HEADERS = m;
  }

  /**
   * Builds the {@code server.request.body} map out of the multipart parts.
   *
   * <p>Only text/plain parts are collected, matching the Jersey reference: file parts are reported
   * separately via {@link #collectFilenames} / {@link #collectFilesContent} and must not also
   * consume the body-map budget. The number of collected values is capped by {@link
   * #MAX_FILES_TO_INSPECT}. The cap counts the total accumulated values across all field names, not
   * the distinct keys: {@code getFormDataMap()} already groups parts by field name, so a per-key
   * cap would be trivially bypassed by repeating the same field name on every part.
   */
  public static Map<String, List<String>> collectBodyMap(MultipartFormDataInput ret) {
    Map<String, List<String>> bodyMap = new HashMap<>();
    int total = 0;
    for (Map.Entry<String, List<InputPart>> e : ret.getFormDataMap().entrySet()) {
      for (InputPart inputPart : e.getValue()) {
        String contentType = contentTypeOf(inputPart);
        if (!isTextPlain(contentType)) {
          continue;
        }
        if (total >= MAX_FILES_TO_INSPECT) {
          return bodyMap;
        }
        bodyMap
            .computeIfAbsent(e.getKey(), k -> new ArrayList<>())
            .add(readContent(inputPart, contentTypeWithDefaultUtf8(contentType)));
        total++;
      }
    }
    return bodyMap;
  }

  // No Content-Type header means RESTEasy defaults the part to text/plain (see InputPart's own
  // javadoc), so a null contentType is treated as text/plain here as well.
  private static boolean isTextPlain(String contentType) {
    if (contentType == null) {
      return true;
    }
    int semi = contentType.indexOf(';');
    String mediaType = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim();
    return mediaType.equalsIgnoreCase("text/plain");
  }

  // Used for the body-map/text-field path only: matches Jersey's own getValue(), which decodes
  // undeclared-charset text parts as UTF-8 instead of falling back to the JVM platform charset
  // (MultipartContentDecoder's default for the filesContent path, kept as-is for parity with the
  // other multipart integrations).
  private static String contentTypeWithDefaultUtf8(String contentType) {
    return MultipartContentDecoder.extractCharset(contentType) == null
        ? (contentType == null ? "charset=UTF-8" : contentType + "; charset=UTF-8")
        : contentType;
  }

  private static String contentTypeOf(InputPart inputPart) {
    if (GET_HEADERS == null) {
      return null;
    }
    try {
      @SuppressWarnings("unchecked")
      Map<String, List<String>> headers = (Map<String, List<String>>) GET_HEADERS.invoke(inputPart);
      if (headers == null) {
        return null;
      }
      List<String> ctHeaders = getHeaderCaseInsensitive(headers, "Content-Type");
      return (ctHeaders != null && !ctHeaders.isEmpty()) ? ctHeaders.get(0) : null;
    } catch (Exception e) {
      // Reflective getHeaders() call failed (unexpected InputPart implementation): fall back to
      // resolving no content-type rather than aborting the whole request's body-map collection.
      log.debug("Failed to read multipart part headers via reflection", e);
      return null;
    }
  }

  public static List<String> collectFilenames(MultipartFormDataInput ret) {
    List<String> filenames = new ArrayList<>();
    if (GET_HEADERS == null) {
      return filenames;
    }
    for (Map.Entry<String, List<InputPart>> e : ret.getFormDataMap().entrySet()) {
      for (InputPart inputPart : e.getValue()) {
        List<String> cdHeaders;
        try {
          @SuppressWarnings("unchecked")
          Map<String, List<String>> headers =
              (Map<String, List<String>>) GET_HEADERS.invoke(inputPart);
          cdHeaders =
              headers != null ? getHeaderCaseInsensitive(headers, "Content-Disposition") : null;
        } catch (Exception ignored) {
          continue;
        }
        if (cdHeaders == null || cdHeaders.isEmpty()) {
          continue;
        }
        String filename = filenameFromContentDisposition(cdHeaders.get(0));
        if (filename != null) {
          filenames.add(filename);
        }
      }
    }
    return filenames;
  }

  public static List<String> collectFilesContent(MultipartFormDataInput ret) {
    List<String> contents = new ArrayList<>();
    if (GET_HEADERS == null) {
      return contents;
    }
    for (Map.Entry<String, List<InputPart>> e : ret.getFormDataMap().entrySet()) {
      for (InputPart inputPart : e.getValue()) {
        if (contents.size() >= MAX_FILES_TO_INSPECT) {
          return contents;
        }
        Map<String, List<String>> headers;
        try {
          @SuppressWarnings("unchecked")
          Map<String, List<String>> h = (Map<String, List<String>>) GET_HEADERS.invoke(inputPart);
          headers = h;
        } catch (Exception ignored) {
          continue;
        }
        if (headers == null) {
          continue;
        }
        List<String> cdHeaders = getHeaderCaseInsensitive(headers, "Content-Disposition");
        if (cdHeaders == null || cdHeaders.isEmpty()) {
          continue;
        }
        // rawFilenameFromContentDisposition returns null if filename attr absent,
        // otherwise returns the value (possibly empty) — both cases warrant content inspection
        if (rawFilenameFromContentDisposition(cdHeaders.get(0)) == null) {
          continue;
        }
        List<String> ctHeaders = getHeaderCaseInsensitive(headers, "Content-Type");
        String contentType = (ctHeaders != null && !ctHeaders.isEmpty()) ? ctHeaders.get(0) : null;
        contents.add(readContent(inputPart, contentType));
      }
    }
    return contents;
  }

  public static BlockingException tryBlock(RequestContext ctx, Flow<Void> flow, String message) {
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      BlockResponseFunction brf = ctx.getBlockResponseFunction();
      if (brf != null) {
        brf.tryCommitBlockingResponse(ctx.getTraceSegment(), rba);
        BlockingException be = new BlockingException(message);
        ctx.getTraceSegment().effectivelyBlocked();
        return be;
      }
    }
    return null;
  }

  static String readContent(InputPart inputPart, String contentType) {
    try (InputStream is = inputPart.getBody(InputStream.class, null)) {
      if (is == null) return "";
      return MultipartContentDecoder.readInputStream(is, MAX_CONTENT_BYTES, contentType);
    } catch (Exception e) {
      // getBody()/readInputStream() can throw unchecked exceptions too (e.g. a MessageBodyReader
      // lookup failure); one bad part must not abort the whole request's body/content collection.
      log.debug("Failed to read multipart part content, returning empty string", e);
      return "";
    }
  }

  private static List<String> getHeaderCaseInsensitive(
      Map<String, List<String>> headers, String name) {
    for (Entry<String, List<String>> entry : headers.entrySet()) {
      if (name.equalsIgnoreCase(entry.getKey())) {
        return entry.getValue();
      }
    }
    return null;
  }

  // Quote-aware: semicolons inside quoted filenames (e.g. filename="a;b.php") are not separators.
  // Outer loop: i advances to each ';' (skipping quoted strings to avoid treating their contents
  // as delimiters), then past MIME linear whitespace (SP/HT) to the start of the parameter name.
  // j is a lookahead used only to find '=' after optional whitespace without committing i until
  // the parameter is confirmed to be "filename"; this avoids confusing "filename*" (RFC 5987) or
  // other "filename"-prefixed parameter names with the plain "filename" parameter.
  public static String filenameFromContentDisposition(String cd) {
    String raw = rawFilenameFromContentDisposition(cd);
    return (raw == null || raw.isEmpty()) ? null : raw;
  }

  // Like filenameFromContentDisposition but returns "" for present-but-empty filename,
  // and null only when the filename parameter is absent entirely.
  static String rawFilenameFromContentDisposition(String cd) {
    if (cd == null) return null;
    int i = 0;
    int len = cd.length();
    while (i < len) {
      while (i < len && cd.charAt(i) != ';') {
        if (cd.charAt(i) == '"') {
          i++;
          while (i < len && cd.charAt(i) != '"') {
            if (cd.charAt(i) == '\\') i++;
            i++;
          }
        }
        i++;
      }
      if (i >= len) break;
      i++;
      while (i < len && (cd.charAt(i) == ' ' || cd.charAt(i) == '\t')) i++;
      if (cd.regionMatches(true, i, "filename", 0, 8)) {
        int j = i + 8;
        while (j < len && (cd.charAt(j) == ' ' || cd.charAt(j) == '\t')) j++;
        if (j < len && cd.charAt(j) == '=') {
          i = j + 1;
          while (i < len && (cd.charAt(i) == ' ' || cd.charAt(i) == '\t')) i++;
          if (i >= len) return "";
          if (cd.charAt(i) == '"') {
            i++;
            StringBuilder sb = new StringBuilder();
            while (i < len && cd.charAt(i) != '"') {
              if (cd.charAt(i) == '\\' && i + 1 < len) i++; // unescape
              sb.append(cd.charAt(i++));
            }
            return sb.toString();
          } else {
            int start = i;
            while (i < len && cd.charAt(i) != ';') i++;
            return cd.substring(start, i).trim();
          }
        }
      }
    }
    return null;
  }
}
