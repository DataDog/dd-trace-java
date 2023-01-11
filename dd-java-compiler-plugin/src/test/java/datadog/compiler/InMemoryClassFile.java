package datadog.compiler;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import javax.tools.SimpleJavaFileObject;

public class InMemoryClassFile extends SimpleJavaFileObject {

  private ByteArrayOutputStream out;

  public InMemoryClassFile(URI uri) {
    super(uri, Kind.CLASS);
  }

  @Override
  public OutputStream openOutputStream() {
    return out = new ByteArrayOutputStream();
  }

  public byte[] getCompiledBinaries() {
    return out.toByteArray();
  }
}
