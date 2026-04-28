package datadog.trace.instrumentation.netty41;

import datadog.trace.api.Config;
import datadog.trace.api.http.MultipartContentDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.multipart.FileUpload;
import java.io.FileInputStream;

/** Reads uploaded file content from a Netty {@link FileUpload} for WAF inspection. */
public final class NettyFileUploadContentReader {
  public static final int MAX_CONTENT_BYTES = Config.get().getAppSecMaxFileContentBytes();
  public static final int MAX_FILES_TO_INSPECT = Config.get().getAppSecMaxFileContentCount();

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

  private NettyFileUploadContentReader() {}
}
