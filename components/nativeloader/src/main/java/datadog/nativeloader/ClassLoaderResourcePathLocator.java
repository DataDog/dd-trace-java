package datadog.nativeloader;

import java.net.URL;
import java.util.Objects;

public final class ClassLoaderResourcePathLocator implements PathLocator {
  private final ClassLoader classLoader;
  private final String baseResource;
  
  public ClassLoaderResourcePathLocator(
    final ClassLoader classLoader,
    final String baseResource)
  {
    this.classLoader = classLoader;
    this.baseResource = baseResource;
  }
  
  @Override
  public URL locate(String component, String path) {
    String fullPath = component == null ? "" : component;
    fullPath = this.baseResource == null ? fullPath : fullPath + "/" + this.baseResource;
    fullPath = fullPath.isEmpty() ? path : fullPath + "/" + path;

    return this.classLoader.getResource(fullPath);
  }
  
  @Override
  public int hashCode() {
	return Objects.hash(this.classLoader, this.baseResource);
  }
  
  @Override
  public boolean equals(Object obj) {
	if (!(obj instanceof ClassLoaderResourcePathLocator)) return false;
	
	ClassLoaderResourcePathLocator that = (ClassLoaderResourcePathLocator)obj;
	return this.classLoader.equals(that.classLoader) && Objects.equals(this.baseResource, that.baseResource);
  }
}