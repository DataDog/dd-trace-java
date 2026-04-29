package datadog.trace.instrumentation.resteasy;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.http.MultipartContentDecoder;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.plugins.providers.multipart.InputPart;
import org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataInput;

public final class MultipartHelper {

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
          cdHeaders = headers != null ? headers.get("Content-Disposition") : null;
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
        List<String> cdHeaders = headers.get("Content-Disposition");
        if (cdHeaders == null || cdHeaders.isEmpty()) {
          continue;
        }
        // rawFilenameFromContentDisposition returns null if filename attr absent,
        // otherwise returns the value (possibly empty) — both cases warrant content inspection
        if (rawFilenameFromContentDisposition(cdHeaders.get(0)) == null) {
          continue;
        }
        List<String> ctHeaders = headers.get("Content-Type");
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
    } catch (IOException ignored) {
      return "";
    }
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
