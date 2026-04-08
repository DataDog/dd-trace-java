package datadog.trace.agent.tooling;

import java.io.IOException;
import net.bytebuddy.dynamic.ClassFileLocator;

/** Locates and shades class-file resources from the advice class-loader. */
public final class ShadedAdviceLocator implements ClassFileLocator {
  private final ClassFileLocator adviceLocator;
  private final AdviceShader adviceShader;

  public ShadedAdviceLocator(ClassLoader adviceLoader, AdviceShader adviceShader) {
    this.adviceLocator = ClassFileLocator.ForClassLoader.of(adviceLoader);
    this.adviceShader = adviceShader;
  }

  @Override
  public Resolution locate(String className) throws IOException {
    final Resolution resolution = adviceLocator.locate(className);
    if (resolution.isResolved()) {
      return new Resolution.Explicit(adviceShader.shadeClass(resolution.resolve()));
    } else {
      return resolution;
    }
  }

  @Override
  public void close() throws IOException {
    adviceLocator.close();
  }
}
