package datadog.trace.api.openfeature;

import datadog.trace.api.featureflag.ufc.v1.ServerConfiguration;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Reads test input through the package-private production parser. */
final class ProductionConfiguration {
  private ProductionConfiguration() {}

  static ServerConfiguration parse(final byte[] bytes) throws IOException {
    try {
      final Class<?> parser = Class.forName("com.datadog.featureflag.UniversalFlagConfigParser");
      final Field instance = parser.getDeclaredField("INSTANCE");
      instance.setAccessible(true);
      final Method deserialize = parser.getDeclaredMethod("deserialize", byte[].class);
      deserialize.setAccessible(true);
      return (ServerConfiguration) deserialize.invoke(instance.get(null), (Object) bytes);
    } catch (ReflectiveOperationException error) {
      throw new IOException("Cannot invoke the production configuration parser", error);
    }
  }
}
