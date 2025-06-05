package utils;

import static datadog.trace.util.Strings.getResourceName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

public class TestClassFileHelper {
  public static byte[] getClassFileBytes(Class<?> clazz) {
    URL resource = clazz.getResource("/" + getResourceName(clazz.getTypeName()));
    byte[] buffer = new byte[4096];
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try (InputStream is = resource.openStream()) {
      int readBytes;
      while ((readBytes = is.read(buffer)) != -1) {
        os.write(buffer, 0, readBytes);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return os.toByteArray();
  }
}
