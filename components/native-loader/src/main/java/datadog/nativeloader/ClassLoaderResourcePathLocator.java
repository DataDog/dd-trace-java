package datadog.nativeloader;

import java.net.URL;
import java.util.Objects;

/** ClassLoaderResourcePathLocator locates library paths inside a {@link ClassLoader} */
final class ClassLoaderResourcePathLocator implements PathLocator {
  private final ClassLoader classLoader;
  private final String baseResource;

  public ClassLoaderResourcePathLocator(final ClassLoader classLoader, final String baseResource) {
    this.classLoader = classLoader;
    this.baseResource = baseResource;
  }

  @Override
  public URL locate(String optionalComponent, String path) {
    return this.classLoader.getResource(PathUtils.concatPath(optionalComponent, this.baseResource, path));
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.classLoader, this.baseResource);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof ClassLoaderResourcePathLocator)) return false;

    ClassLoaderResourcePathLocator that = (ClassLoaderResourcePathLocator) obj;
    return this.classLoader.equals(that.classLoader)
        && Objects.equals(this.baseResource, that.baseResource);
  }
}
