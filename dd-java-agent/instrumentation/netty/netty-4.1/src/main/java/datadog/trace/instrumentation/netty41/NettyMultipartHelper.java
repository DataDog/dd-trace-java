package datadog.trace.instrumentation.netty41;

import datadog.trace.api.Config;
import datadog.trace.api.http.MultipartContentDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class NettyMultipartHelper {
  public static final int MAX_CONTENT_BYTES = Config.get().getAppSecMaxFileContentBytes();
  public static final int MAX_FILES_TO_INSPECT = Config.get().getAppSecMaxFileContentCount();

  /**
   * Iterates multipart body parts populating the provided output collections. Pass {@code null} for
   * any collection to skip that category. Returns any {@link IOException} encountered reading an
   * attribute value, wrapped as {@link UndeclaredThrowableException}, or {@code null} if none.
   */
  public static RuntimeException collectBodyData(
      List<InterfaceHttpData> parts,
      Map<String, List<String>> attributes,
      List<String> filenames,
      List<String> filesContent) {
    RuntimeException exc = null;
    for (InterfaceHttpData data : parts) {
      if (attributes != null
          && data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
        String name = data.getName();
        List<String> values = attributes.get(name);
        if (values == null) {
          attributes.put(name, values = new ArrayList<>(1));
        }
        try {
          values.add(((Attribute) data).getValue());
        } catch (IOException e) {
          exc = new UndeclaredThrowableException(e);
        }
      } else if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
        FileUpload fileUpload = (FileUpload) data;
        String filename = fileUpload.getFilename();
        if (filenames != null && filename != null && !filename.isEmpty()) {
          filenames.add(filename);
        }
        if (filesContent != null && filesContent.size() < MAX_FILES_TO_INSPECT) {
          filesContent.add(readContent(fileUpload));
        }
      }
    }
    return exc;
  }

  public static String readContent(FileUpload fileUpload) {
    try {
      if (fileUpload.isInMemory()) {
        ByteBuf buf = fileUpload.getByteBuf();
        int length = Math.min(MAX_CONTENT_BYTES, buf.readableBytes());
        byte[] bytes = new byte[length];
        buf.getBytes(buf.readerIndex(), bytes);
        return MultipartContentDecoder.decodeBytes(bytes, length, fileUpload.getContentType());
      } else {
        try (FileInputStream fis = new FileInputStream(fileUpload.getFile())) {
          return MultipartContentDecoder.readInputStream(
              fis, MAX_CONTENT_BYTES, fileUpload.getContentType());
        }
      }
    } catch (Exception ignored) {
      return "";
    }
  }

  private NettyMultipartHelper() {}
}
