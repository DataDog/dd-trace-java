package datadog.trace.instrumentation.jetty8;

import static datadog.trace.api.gateway.Events.EVENTS;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.CallbackProvider;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import javax.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Helper for extracting filenames and form-field values from Servlet 3.0 {@link Part} objects.
 *
 * <p>{@code Part.getSubmittedFileName()} was added in Servlet 3.1 (Jetty 9.1+); for Jetty 8.x we
 * must parse the {@code Content-Disposition} header manually.
 */
public class PartHelper {

  private static final Logger log = LoggerFactory.getLogger(PartHelper.class);

  private PartHelper() {}

  // Lazily resolves MultiPartInputStream.getParts() as a MethodHandle on first class access.
  // Uses IODH so no volatile is needed; the JVM class-loading guarantee ensures safe publication.
  private static final class MpiGetPartsHolder {
    static final MethodHandle GET_PARTS;

    static {
      MethodHandle h = null;
      try {
        Class<?> cls =
            Class.forName(
                "org.eclipse.jetty.util.MultiPartInputStream",
                false,
                MpiGetPartsHolder.class.getClassLoader());
        h = MethodHandles.lookup().unreflect(cls.getMethod("getParts"));
      } catch (Exception ignored) {
        // class or method not available — getAllParts() falls back to singleton
      }
      GET_PARTS = h;
    }
  }

  /**
   * Returns all parts from a {@code MultiPartInputStream} object (already-parsed, no re-trigger).
   * Falls back to a singleton of {@code singlePart} if reflection fails or the collection is empty.
   */
  public static Collection<?> getAllParts(Object multiPartInputStream, Part singlePart) {
    if (multiPartInputStream != null) {
      MethodHandle mh = MpiGetPartsHolder.GET_PARTS;
      if (mh != null) {
        try {
          @SuppressWarnings("unchecked")
          Collection<?> all = (Collection<?>) mh.invoke(multiPartInputStream);
          if (all != null && !all.isEmpty()) {
            return all;
          }
        } catch (Throwable e) {
          log.debug("getAllParts: MethodHandle invocation failed, falling back to singleton", e);
        }
      }
    }
    return singlePart != null ? Collections.singletonList(singlePart) : Collections.emptyList();
  }

  /**
   * Returns filenames found in {@code parts} by parsing each part's {@code Content-Disposition}
   * header for a {@code filename=} parameter.
   */
  public static List<String> extractFilenames(Collection<?> parts) {
    if (parts == null || parts.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> filenames = new ArrayList<>(parts.size());
    for (Object obj : parts) {
      try {
        String filename = filenameFromPart((Part) obj);
        if (filename != null && !filename.isEmpty()) {
          filenames.add(filename);
        }
      } catch (Exception e) {
        log.debug("extractFilenames: skipping malformed part", e);
      }
    }
    return filenames;
  }

  /**
   * Returns a name→values map of form-field parts (those without a {@code filename=} parameter).
   * File-upload parts are skipped to avoid reading potentially large content.
   */
  public static Map<String, List<String>> extractFormFields(Collection<?> parts) {
    if (parts == null || parts.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, List<String>> result = new LinkedHashMap<>();
    for (Object obj : parts) {
      try {
        Part part = (Part) obj;
        if (filenameFromPart(part) != null) {
          continue; // file-upload part — skip
        }
        String name = part.getName();
        if (name == null) {
          continue;
        }
        String value = readPartContent(part);
        if (value == null) {
          continue;
        }
        result.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
      } catch (Exception e) {
        log.debug("extractFormFields: skipping malformed part", e);
      }
    }
    return result;
  }

  /**
   * Extracts the {@code filename} value from a {@code Content-Disposition} header, or {@code null}
   * if the part has no filename (i.e. it is a plain form field).
   *
   * <p>Uses a quote-aware parser so that semicolons inside a quoted filename (e.g. {@code
   * filename="shell;evil.php"}) are not mistaken for parameter separators.
   */
  static String filenameFromPart(Part part) {
    String cd = part.getHeader("Content-Disposition");
    if (cd == null) {
      return null;
    }
    int len = cd.length();
    int i = 0;
    while (i < len) {
      // Skip separators between parameters
      while (i < len && (cd.charAt(i) == ';' || cd.charAt(i) == ' ' || cd.charAt(i) == '\t')) {
        i++;
      }
      if (i >= len) break;
      // Read parameter name (up to '=' or ';')
      int nameStart = i;
      while (i < len && cd.charAt(i) != '=' && cd.charAt(i) != ';') {
        i++;
      }
      boolean isFilename = "filename".equalsIgnoreCase(cd.substring(nameStart, i).trim());
      if (i >= len || cd.charAt(i) == ';') {
        // Value-less token (e.g. "form-data") — skip
        continue;
      }
      i++; // skip '='
      String value;
      if (i < len && cd.charAt(i) == '"') {
        i++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (i < len && cd.charAt(i) != '"') {
          if (cd.charAt(i) == '\\' && i + 1 < len) {
            i++; // consume escape backslash, add next char literally
          }
          sb.append(cd.charAt(i++));
        }
        if (i < len) i++; // skip closing quote
        value = sb.toString();
      } else {
        int valueStart = i;
        while (i < len && cd.charAt(i) != ';') {
          i++;
        }
        value = cd.substring(valueStart, i).trim();
      }
      if (isFilename) {
        // Return empty string (not null) so callers can distinguish "filename present but empty"
        // from "no filename parameter". extractFormFields() uses != null to skip file parts,
        // so empty string correctly prevents buffering a file-upload body with filename="".
        return value;
      }
    }
    return null;
  }

  /**
   * Fires the {@code requestBodyProcessed} IG event for form-field parts in {@code parts} and
   * returns a {@link BlockingException} if the WAF requests blocking, or {@code null} otherwise.
   */
  public static BlockingException fireBodyProcessedEvent(
      Collection<?> parts, RequestContext reqCtx) {
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, Object, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestBodyProcessed());
    if (callback == null) {
      return null;
    }
    Map<String, List<String>> formFields = extractFormFields(parts);
    if (formFields.isEmpty()) {
      return null;
    }
    Flow<Void> flow = callback.apply(reqCtx, formFields);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
      if (brf != null) {
        if (brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba)) {
          reqCtx.getTraceSegment().effectivelyBlocked();
          return new BlockingException("Blocked request (multipart form fields)");
        }
      }
    }
    return null;
  }

  /**
   * Fires the {@code requestFilesFilenames} IG event for file-upload parts in {@code parts} and
   * returns a {@link BlockingException} if the WAF requests blocking, or {@code null} otherwise.
   */
  public static BlockingException fireFilenamesEvent(Collection<?> parts, RequestContext reqCtx) {
    CallbackProvider cbp = AgentTracer.get().getCallbackProvider(RequestContextSlot.APPSEC);
    BiFunction<RequestContext, List<String>, Flow<Void>> callback =
        cbp.getCallback(EVENTS.requestFilesFilenames());
    if (callback == null) {
      return null;
    }
    List<String> filenames = extractFilenames(parts);
    if (filenames.isEmpty()) {
      return null;
    }
    Flow<Void> flow = callback.apply(reqCtx, filenames);
    Flow.Action action = flow.getAction();
    if (action instanceof Flow.Action.RequestBlockingAction) {
      Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
      BlockResponseFunction brf = reqCtx.getBlockResponseFunction();
      if (brf != null) {
        if (brf.tryCommitBlockingResponse(reqCtx.getTraceSegment(), rba)) {
          reqCtx.getTraceSegment().effectivelyBlocked();
          return new BlockingException("Blocked request (multipart file upload)");
        }
      }
    }
    return null;
  }

  private static String readPartContent(Part part) {
    Charset charset = charsetFromContentType(part.getContentType());
    // Bound the buffered form-field text by the file-content byte cap. There is no dedicated
    // "max form-field bytes" config, so we intentionally reuse getAppSecMaxFileContentBytes():
    // this is the only framework that must manually buffer form-field text (Servlet 3.0 has no
    // container-side bound), and without a cap a single huge text field could exhaust the heap.
    int maxBytes = Config.get().getAppSecMaxFileContentBytes();
    try (InputStream is = part.getInputStream()) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int total = 0;
      int read;
      while (total < maxBytes
          && (read = is.read(buf, 0, Math.min(buf.length, maxBytes - total))) != -1) {
        baos.write(buf, 0, read);
        total += read;
      }
      return new String(baos.toByteArray(), charset);
    } catch (IOException e) {
      log.debug("readPartContent: stream read failed", e);
      return null;
    }
  }

  /**
   * Parses the {@code charset} parameter from a {@code Content-Type} header value (e.g. {@code
   * text/plain; charset=ISO-8859-1}). Returns UTF-8 when absent, unknown, or {@code null}.
   */
  static Charset charsetFromContentType(String contentType) {
    if (contentType != null) {
      int idx = contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
      if (idx >= 0) {
        String name = contentType.substring(idx + 8).trim();
        if (!name.isEmpty() && name.charAt(0) == '"') {
          name = name.substring(1);
          int end = name.indexOf('"');
          if (end >= 0) name = name.substring(0, end);
        } else {
          int end = 0;
          while (end < name.length()
              && name.charAt(end) != ';'
              && !Character.isWhitespace(name.charAt(end))) {
            end++;
          }
          name = name.substring(0, end);
        }
        try {
          return Charset.forName(name);
        } catch (Exception ignored) {
          // unknown or unsupported charset name — fall back to UTF-8
        }
      }
    }
    return StandardCharsets.UTF_8;
  }
}
