package datadog.trace.instrumentation.jetty8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.servlet.http.Part;

/**
 * Helper for extracting filenames and form-field values from Servlet 3.0 {@link Part} objects.
 *
 * <p>{@code Part.getSubmittedFileName()} was added in Servlet 3.1 (Jetty 9.1+); for Jetty 8.x we
 * must parse the {@code Content-Disposition} header manually.
 */
public class PartHelper {

  private PartHelper() {}

  /**
   * Returns filenames found in {@code parts} by parsing each part's {@code Content-Disposition}
   * header for a {@code filename=} parameter.
   */
  public static List<String> extractFilenames(Collection<?> parts) {
    if (parts == null || parts.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> filenames = new ArrayList<>();
    for (Object obj : parts) {
      String filename = filenameFromPart((Part) obj);
      if (filename != null && !filename.isEmpty()) {
        filenames.add(filename);
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
      List<String> values = result.get(name);
      if (values == null) {
        values = new ArrayList<>();
        result.put(name, values);
      }
      values.add(value);
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

  private static String readPartContent(Part part) {
    try (InputStream is = part.getInputStream()) {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int read;
      while ((read = is.read(buf)) != -1) {
        baos.write(buf, 0, read);
      }
      return baos.toString("UTF-8");
    } catch (IOException e) {
      return null;
    }
  }
}
