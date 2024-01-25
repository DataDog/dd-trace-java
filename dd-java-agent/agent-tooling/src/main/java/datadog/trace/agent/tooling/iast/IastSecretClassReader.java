package datadog.trace.agent.tooling.iast;

import datadog.trace.api.function.TriConsumer;
import java.util.Map;
import javax.annotation.Nonnull;
import org.objectweb.asm.ClassReader;

public class IastSecretClassReader {

  public static final IastSecretClassReader INSTANCE = new IastSecretClassReader();

  public void readClass(
      final Map<String, String> secrets,
      final @Nonnull byte[] classFile,
      @Nonnull TriConsumer consumer) {
    ClassReader classReader = new ClassReader(classFile);
    IastSecretVisitor classVisitor = new IastSecretVisitor(secrets, consumer);
    classReader.accept(classVisitor, 0);
  }
}
