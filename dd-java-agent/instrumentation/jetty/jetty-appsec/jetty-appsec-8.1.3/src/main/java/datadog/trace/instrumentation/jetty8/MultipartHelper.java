package datadog.trace.instrumentation.jetty8;

public class MultipartHelper {

  private MultipartHelper() {}

  /**
   * Extracts the filename value from a {@code Content-Disposition} header string.
   *
   * <p>Jetty 8 implements Servlet 3.0, where {@code Part.getSubmittedFileName()} does not exist.
   * This method parses the filename from the header manually.
   *
   * <p>Examples of handled inputs:
   *
   * <pre>
   *   form-data; name="file"; filename="photo.jpg"  → "photo.jpg"
   *   form-data; name="file"; filename=photo.jpg    → "photo.jpg"
   * </pre>
   *
   * @return the filename, or {@code null} if not present or empty
   */
  public static String filenameFromContentDisposition(String cd) {
    if (cd == null) {
      return null;
    }
    for (String tok : cd.split(";")) {
      tok = tok.trim();
      if (tok.startsWith("filename=")) {
        String name = tok.substring(9).trim();
        if (name.startsWith("\"") && name.endsWith("\"")) {
          name = name.substring(1, name.length() - 1);
        }
        return name.isEmpty() ? null : name;
      }
    }
    return null;
  }
}
