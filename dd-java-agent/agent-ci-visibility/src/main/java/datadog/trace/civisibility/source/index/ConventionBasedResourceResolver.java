package datadog.trace.civisibility.source.index;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.List;

public class ConventionBasedResourceResolver implements ResourceResolver {

  private final FileSystem fileSystem;
  private final List<String> resourceFolderNames;

  public ConventionBasedResourceResolver(FileSystem fileSystem, List<String> resourceFolderNames) {
    this.fileSystem = fileSystem;
    this.resourceFolderNames = resourceFolderNames;
  }

  /**
   * Given absolute path to a resource file, returns resource root - the enclosing folder that is
   * considered as a resource folder by the project's build system. Resource folder is a folder
   * containing non-code resources that are copied to a target/build folder during the project's
   * build.
   *
   * <p>The implementation of this method is very naive: it examines the resource's path looking for
   * segments that match conventional resource folder names ("resources/", "java/", etc.).
   *
   * @param resourceFile Absolute path to a resource file
   * @return Resource root
   */
  @Override
  public Path getResourceRoot(Path resourceFile) throws IOException {
    String pathAsString = resourceFile.toString();
    for (String resourceFolderName : resourceFolderNames) {
      int idx = pathAsString.indexOf(resourceFolderName);
      if (idx >= 0) {
        return fileSystem.getPath(pathAsString.substring(0, idx + resourceFolderName.length()));
      }
    }
    return null;
  }
}
