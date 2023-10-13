package foo.bar.smoketest;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import javax.servlet.http.Part;

public class MockPart implements Part {
  String name;
  String headerValue;

  public MockPart(String name, String headerValue) {
    this.name = name;
    this.headerValue = headerValue;
  }

  @Override
  public InputStream getInputStream() throws IOException {
    return null;
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
  public String getSubmittedFileName() {
    return null;
  }

  @Override
  public long getSize() {
    return 0;
  }

  @Override
  public void write(String fileName) throws IOException {}

  @Override
  public void delete() throws IOException {}

  @Override
  public String getHeader(String name) {
    return headerValue;
  }

  @Override
  public Collection<String> getHeaders(String name) {
    return Collections.singleton(headerValue);
  }

  @Override
  public Collection<String> getHeaderNames() {
    return Collections.singleton(name);
  }
}
