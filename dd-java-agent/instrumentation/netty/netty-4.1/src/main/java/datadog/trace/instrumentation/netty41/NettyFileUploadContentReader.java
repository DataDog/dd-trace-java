package datadog.trace.instrumentation.netty41;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.multipart.FileUpload;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Reads uploaded file content from a Netty {@link FileUpload} for WAF inspection. */
public final class NettyFileUploadContentReader {
  public static final int MAX_CONTENT_BYTES = 4096;

  public static String readContent(FileUpload fileUpload) {
    try {
      if (fileUpload.isInMemory()) {
        ByteBuf buf = fileUpload.getByteBuf();
        int length = Math.min(MAX_CONTENT_BYTES, buf.readableBytes());
        byte[] bytes = new byte[length];
        buf.getBytes(buf.readerIndex(), bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
      } else {
        byte[] bytes = new byte[MAX_CONTENT_BYTES];
        int total = 0;
        int n;
        try (FileInputStream fis = new FileInputStream(fileUpload.getFile())) {
          while (total < MAX_CONTENT_BYTES
              && (n = fis.read(bytes, total, MAX_CONTENT_BYTES - total)) != -1) {
            total += n;
          }
        }
        return new String(bytes, 0, total, StandardCharsets.ISO_8859_1);
      }
    } catch (Exception ignored) {
      return "";
    }
  }

  private NettyFileUploadContentReader() {}
}
