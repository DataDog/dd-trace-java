package foo.bar.smoketest;

import java.io.IOException;
import java.io.InputStream;
import org.apache.commons.fileupload.FileItemHeaders;
import org.apache.commons.fileupload.FileItemStream;

public class MockFileItemStream implements FileItemStream {
  private final String name;

  private final InputStream inputStream;

  public MockFileItemStream(final String name, final InputStream inputStream) {
    this.name = name;
    this.inputStream = inputStream;
  }

  public FileItemHeaders getHeaders() {
    return null;
  }

  public void setHeaders(FileItemHeaders var1) {}

  public InputStream openStream() throws IOException {
    return inputStream;
  }

  public String getContentType() {
    return null;
  }

  public String getName() {
    return name;
  }

  public String getFieldName() {
    return null;
  }

  public boolean isFormField() {
    return true;
  }
}
