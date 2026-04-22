package datadog.trace.instrumentation.commons.fileupload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.fileupload.FileItem;

/** Reads uploaded file content for WAF inspection. */
public final class FileItemContentReader {
  public static final int MAX_CONTENT_BYTES = 4096;
  public static final int MAX_FILES_TO_INSPECT = 25;

  public static List<String> readContents(List<FileItem> fileItems) {
    List<String> result = new ArrayList<>();
    for (FileItem fileItem : fileItems) {
      if (result.size() >= MAX_FILES_TO_INSPECT) {
        break;
      }
      if (fileItem.isFormField()) {
        continue;
      }
      result.add(readContent(fileItem));
    }
    return result;
  }

  public static String readContent(FileItem fileItem) {
    try (InputStream is = fileItem.getInputStream()) {
      byte[] buf = new byte[MAX_CONTENT_BYTES];
      int total = 0;
      int n;
      while (total < MAX_CONTENT_BYTES
          && (n = is.read(buf, total, MAX_CONTENT_BYTES - total)) != -1) {
        total += n;
      }
      return new String(buf, 0, total, StandardCharsets.ISO_8859_1);
    } catch (IOException ignored) {
      return "";
    }
  }

  private FileItemContentReader() {}
}
