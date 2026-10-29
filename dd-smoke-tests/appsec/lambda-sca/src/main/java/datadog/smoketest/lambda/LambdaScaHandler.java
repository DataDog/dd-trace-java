package datadog.smoketest.lambda;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Calls the watched method inside an instrumented Lambda invocation. */
public final class LambdaScaHandler implements RequestStreamHandler {
  @Override
  public void handleRequest(InputStream input, OutputStream output, Context context)
      throws IOException {
    try {
      warmInvocation();
      output.write("OK".getBytes(StandardCharsets.UTF_8));
    } catch (ReflectiveOperationException failure) {
      throw new IOException(failure);
    }
  }

  private static void warmInvocation() throws ReflectiveOperationException {
    Class<?> extractor = Class.forName("com.github.junrar.LocalFolderExtractor");
    Constructor<?> constructor = extractor.getDeclaredConstructor(File.class);
    constructor.setAccessible(true);
    Object instance = constructor.newInstance(new File(System.getProperty("java.io.tmpdir")));
    Method method =
        extractor.getDeclaredMethod(
            "createDirectory", Class.forName("com.github.junrar.rarfile.FileHeader"));
    method.setAccessible(true);
    try {
      // LambdaScaSmokeTest asserts the exact line of this call; update it if this line moves.
      method.invoke(instance, (Object) null);
    } catch (InvocationTargetException failure) {
      // Reaching method entry is sufficient; no archive extraction or filesystem write is needed.
      if (!(failure.getCause() instanceof NullPointerException)) {
        throw failure;
      }
    }
  }
}
