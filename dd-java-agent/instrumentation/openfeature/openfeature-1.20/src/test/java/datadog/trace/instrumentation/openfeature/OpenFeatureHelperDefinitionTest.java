package datadog.trace.instrumentation.openfeature;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

class OpenFeatureHelperDefinitionTest {
  @Test
  void helpersDefineInOrderWithoutApplicationProviderOrCoreClasses() throws Exception {
    HelperLoader loader = new HelperLoader(getClass().getClassLoader());
    for (String name : new OpenFeatureAPIInstrumentation().helperClassNames()) {
      try (InputStream input =
          getClass().getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
        assertNotNull(input, name);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
          output.write(buffer, 0, count);
        }
        loader.defineHelper(name, output.toByteArray());
      }
    }
    assertSame(
        loader,
        loader.loadClass("com.datadog.featureflag.core.EvaluationContext").getClassLoader());
    assertSame(
        loader, loader.loadClass("datadog.trace.api.openfeature.DDEvaluator$1").getClassLoader());
  }

  private static final class HelperLoader extends ClassLoader {
    HelperLoader(ClassLoader parent) {
      super(parent);
    }

    void defineHelper(String name, byte[] bytes) {
      defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (name.startsWith("com.datadog.featureflag.core.")
          || name.startsWith("datadog.trace.api.openfeature.")) {
        Class<?> helper = findLoadedClass(name);
        if (helper == null) {
          throw new ClassNotFoundException(name);
        }
        if (resolve) {
          resolveClass(helper);
        }
        return helper;
      }
      return super.loadClass(name, resolve);
    }
  }
}
