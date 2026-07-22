package datadog.trace.instrumentation.undertow;

import datadog.trace.api.Config;
import datadog.trace.api.http.MultipartContentDecoder;
import datadog.trace.api.internal.VisibleForTesting;
import io.undertow.server.handlers.form.FormData;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class FormDataContentHelper {

  private static final Logger log = LoggerFactory.getLogger(FormDataContentHelper.class);

  public static final int MAX_CONTENT_BYTES = Config.get().getAppSecMaxFileContentBytes();
  public static final int MAX_FILES_TO_INSPECT = Config.get().getAppSecMaxFileContentCount();

  // Undertow 2.2+ added getFileItem() to FormValue for in-memory uploads.
  // In 2.0 all uploads are always on disk so getPath() suffices.
  private static final Method GET_FILE_ITEM;
  private static final Method FILE_ITEM_GET_INPUT_STREAM;

  static {
    Method gfi = null;
    Method gis = null;
    try {
      gfi = FormData.FormValue.class.getMethod("getFileItem");
      gis = gfi.getReturnType().getMethod("getInputStream");
    } catch (Exception ignored) {
    }
    GET_FILE_ITEM = gfi;
    FILE_ITEM_GET_INPUT_STREAM = gis;
  }

  public static List<String> collectContents(FormData attachment) {
    List<String> result = new ArrayList<>(MAX_FILES_TO_INSPECT);
    for (String key : attachment) {
      for (FormData.FormValue formValue : attachment.get(key)) {
        if (result.size() >= MAX_FILES_TO_INSPECT) {
          return result;
        }
        try {
          // null means no filename attribute → form field → skip
          if (formValue.getFileName() == null) {
            continue;
          }
          result.add(readContent(formValue));
        } catch (Exception e) {
          log.debug("Failed to process form value", e);
        }
      }
    }
    return result;
  }

  @VisibleForTesting
  static String readContent(FormData.FormValue formValue) {
    String contentType = null;
    HeaderMap headers = formValue.getHeaders();
    if (headers != null) {
      contentType = headers.getFirst(Headers.CONTENT_TYPE);
    }

    // Try getPath() first: works for 2.0 (all files on disk) and 2.2+ disk files.
    try {
      Path path = formValue.getPath();
      try (InputStream is = Files.newInputStream(path)) {
        return MultipartContentDecoder.readInputStream(is, MAX_CONTENT_BYTES, contentType);
      }
    } catch (Exception ignored) {
      // In Undertow 2.2+, in-memory uploads throw here (no path).
    }

    // Fallback for Undertow 2.2+ in-memory uploads via cached reflection.
    if (GET_FILE_ITEM != null && FILE_ITEM_GET_INPUT_STREAM != null) {
      try {
        Object fileItem = GET_FILE_ITEM.invoke(formValue);
        if (fileItem != null) {
          try (InputStream is = (InputStream) FILE_ITEM_GET_INPUT_STREAM.invoke(fileItem)) {
            return MultipartContentDecoder.readInputStream(is, MAX_CONTENT_BYTES, contentType);
          }
        }
      } catch (Exception e) {
        log.debug("Failed to read in-memory upload via reflection", e);
      }
    }

    return "";
  }

  private FormDataContentHelper() {}
}
