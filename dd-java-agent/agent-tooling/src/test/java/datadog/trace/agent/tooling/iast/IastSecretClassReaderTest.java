package datadog.trace.agent.tooling.iast;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import datadog.trace.api.function.TriConsumer;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.Test;

class IastSecretClassReaderTest extends DDJavaSpecification {

  @Test
  @SuppressWarnings("unchecked")
  void readClassReportsHardcodedSecrets() throws IOException {
    // given
    String ageSecret = "age-secret-key";
    String githubSecret = "github-app-token";
    Map<String, String> secrets = new HashMap<>();
    secrets.put(WithHardcodedSecret.getSecret(), ageSecret);
    secrets.put(WithHardcodedSecret.getSecret2(), githubSecret);
    byte[] classFile = readClassBytes(WithHardcodedSecret.class);
    TriConsumer<String, String, Integer> consumer = mock(TriConsumer.class);

    // when
    IastSecretClassReader.INSTANCE.readClass(secrets, classFile, consumer);

    // then
    verify(consumer).accept("getSecret", ageSecret, 9);
    verify(consumer).accept("getSecret2", githubSecret, 13);
  }

  private static byte[] readClassBytes(Class<?> clazz) throws IOException {
    String classResourceName = clazz.getName().replace('.', '/') + ".class";
    try (InputStream is = clazz.getClassLoader().getResourceAsStream(classResourceName)) {
      assertNotNull(is, "Could not find class resource: " + classResourceName);
      return IOUtils.toByteArray(is);
    }
  }
}
