package foo.bar.smoketest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemHeaders;

public class MockFileItem implements FileItem {
  private final String name;

  private final InputStream inputStream;

  public MockFileItem(final String name, final InputStream inputStream) {
    this.name = name;
    this.inputStream = inputStream;
  }

  @Override
  public FileItemHeaders getHeaders() {
    return null;
  }

  @Override
  public void setHeaders(FileItemHeaders var1) {}

  @Override
  public InputStream getInputStream() throws IOException {
    return inputStream;
  }

  @Override
  public String getContentType() {
    return null;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public boolean isInMemory() {
    return true;
  }

  @Override
  public long getSize() {
    return 0;
  }

  @Override
  public byte[] get() {
    return null;
  }

  @Override
  public String getString(String var1) throws UnsupportedEncodingException {
    return null;
  }

  @Override
  public String getString() {
    return null;
  }

  @Override
  public void write(File var1) throws Exception {}

  @Override
  public void delete() {}

  @Override
  public String getFieldName() {
    return null;
  }

  @Override
  public void setFieldName(String var1) {}

  @Override
  public boolean isFormField() {
    return true;
  }

  @Override
  public void setFormField(boolean var1) {}

  @Override
  public OutputStream getOutputStream() throws IOException {
    return null;
  }
}
