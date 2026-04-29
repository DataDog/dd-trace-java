package datadog.trace.instrumentation.commons.fileupload;

import datadog.trace.api.Config;
import datadog.trace.api.http.MultipartContentDecoder;
import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.fileupload.FileItem;

/** Reads uploaded file content for WAF inspection. */
public final class FileItemContentReader {
  public static final int MAX_CONTENT_BYTES = Config.get().getAppSecMaxFileContentBytes();

  public static String readContent(FileItem fileItem) {
    try (InputStream is = fileItem.getInputStream()) {
      return MultipartContentDecoder.readInputStream(
          is, MAX_CONTENT_BYTES, fileItem.getContentType());
    } catch (IOException ignored) {
      return "";
    }
  }

  private FileItemContentReader() {}
}
