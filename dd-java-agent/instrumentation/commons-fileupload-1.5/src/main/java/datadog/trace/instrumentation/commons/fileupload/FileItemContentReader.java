package datadog.trace.instrumentation.commons.fileupload;

import datadog.trace.api.http.MultipartContentDecoder;
import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.fileupload.FileItem;

/** Reads uploaded file content for WAF inspection. */
public final class FileItemContentReader {
  public static final int MAX_CONTENT_BYTES = 4096;
  public static final int MAX_FILES_TO_INSPECT = 25;

  public static String readContent(FileItem fileItem) {
    try (InputStream is = fileItem.getInputStream()) {
      byte[] buf = new byte[MAX_CONTENT_BYTES];
      int total = 0;
      int n;
      while (total < MAX_CONTENT_BYTES
          && (n = is.read(buf, total, MAX_CONTENT_BYTES - total)) != -1) {
        total += n;
      }
      return MultipartContentDecoder.decodeBytes(buf, total, fileItem.getContentType());
    } catch (IOException ignored) {
      return "";
    }
  }

  private FileItemContentReader() {}
}
