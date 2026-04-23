package datadog.trace.instrumentation.undertow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.undertow.server.handlers.form.FormData;
import io.undertow.util.HeaderMap;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormDataMapTest {

  @TempDir Path tempDir;

  @Test
  void textFieldIsIncluded() {
    FormData fd = new FormData(10);
    fd.add("name", "John");
    FormDataMap map = new FormDataMap(fd);

    assertTrue(map.containsKey("name"));
    Collection<String> values = map.get("name");
    assertEquals(1, values.size());
    assertTrue(values.contains("John"));
  }

  @Test
  void multipleTextValuesForSameKey() {
    FormData fd = new FormData(10);
    fd.add("tag", "foo");
    fd.add("tag", "bar");
    FormDataMap map = new FormDataMap(fd);

    Collection<String> values = map.get("tag");
    assertEquals(2, values.size());
    assertTrue(values.contains("foo"));
    assertTrue(values.contains("bar"));
  }

  @Test
  void diskFileIsExcluded() throws IOException {
    Path file = Files.createTempFile(tempDir, "upload", ".txt");
    FormData fd = new FormData(10);
    fd.add("upload", file, "evil.php", new HeaderMap());
    FormDataMap map = new FormDataMap(fd);

    assertTrue(map.containsKey("upload"));
    assertTrue(map.get("upload").isEmpty());
  }

  @Test
  void inMemoryFileIsExcluded() throws Exception {
    // In undertow 2.2+, isFile() returns false for in-memory uploads, but getFileName() is still
    // set. Verify our check (getFileName() == null) correctly excludes these uploads too.
    FormData fd = new FormData(10);
    addInMemoryFileValue(fd, "file", "evil.php");
    FormDataMap map = new FormDataMap(fd);

    assertTrue(map.containsKey("file"));
    assertTrue(map.get("file").isEmpty());
  }

  @Test
  void mixedTextAndFileFields() throws IOException {
    Path file = Files.createTempFile(tempDir, "upload", ".txt");
    FormData fd = new FormData(10);
    fd.add("name", "John");
    fd.add("email", "john@example.com");
    fd.add("upload", file, "evil.php", new HeaderMap());
    FormDataMap map = new FormDataMap(fd);

    assertEquals(3, map.size());
    assertTrue(map.get("name").contains("John"));
    assertTrue(map.get("email").contains("john@example.com"));
    assertTrue(map.get("upload").isEmpty());
  }

  @Test
  void emptyFormData() {
    FormData fd = new FormData(10);
    FormDataMap map = new FormDataMap(fd);

    assertTrue(map.isEmpty());
    assertEquals(0, map.size());
  }

  @SuppressWarnings("unchecked")
  private static void addInMemoryFileValue(FormData fd, String name, String filename)
      throws Exception {
    Field valuesField = FormData.class.getDeclaredField("values");
    valuesField.setAccessible(true);
    Map<String, Deque<FormData.FormValue>> values =
        (Map<String, Deque<FormData.FormValue>>) valuesField.get(fd);

    FormData.FormValue inMemory =
        new FormData.FormValue() {
          @Override
          public String getValue() {
            return "";
          }

          @Override
          public boolean isFile() {
            return false;
          }

          @Override
          public String getFileName() {
            return filename;
          }

          @Override
          public Path getPath() {
            return null;
          }

          @Override
          public File getFile() {
            return null;
          }

          @Override
          public HeaderMap getHeaders() {
            return null;
          }

          // Methods added in undertow 2.2.x
          @Override
          public String getCharset() {
            return null;
          }

          @Override
          public FormData.FileItem getFileItem() {
            return null;
          }

          @Override
          public boolean isFileItem() {
            return false;
          }

          @Override
          public boolean isBigField() {
            return false;
          }
        };

    Deque<FormData.FormValue> deque = new ArrayDeque<>();
    deque.add(inMemory);
    values.put(name, deque);
  }
}
