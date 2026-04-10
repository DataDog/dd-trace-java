package datadog.trace.instrumentation.jetty1010;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.servlet.http.Part;

public class MultipartHelper {

  private MultipartHelper() {}

  /**
   * Extracts non-null, non-empty filenames from a collection of multipart {@link Part}s using
   * {@link Part#getSubmittedFileName()} (Servlet 3.1+, Jetty 10.0.10+).
   *
   * @return list of filenames; never {@code null}, may be empty
   */
  public static List<String> extractFilenames(Collection<Part> parts) {
    if (parts == null || parts.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> filenames = new ArrayList<>();
    for (Part part : parts) {
      String name = part.getSubmittedFileName();
      if (name != null && !name.isEmpty()) {
        filenames.add(name);
      }
    }
    return filenames;
  }
}
