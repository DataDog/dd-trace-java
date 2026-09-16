package test;

import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.ImageNameSubstitutor;

/**
 * A custom {@link ImageNameSubstitutor} implementation that rewrites Docker image names to use
 * Datadog's internal registry {@code registry.ddbuild.io} when running in a CI environment.
 *
 * <p>Images from DockerHub already mirrored by {@code registry.ddbuild.io} via environment variable
 * {@code TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX}
 *
 * <p>For images from other repositories custom image name substitutor should be implemented.
 * Internal registry is faster and not affected by rate limiting.
 */
public class DataDogRegistryImageNameSubstitutor extends ImageNameSubstitutor {
  @Override
  public DockerImageName apply(DockerImageName original) {
    String name = original.asCanonicalNameString();

    if (System.getenv("CI") != null) {
      // For now, we need to mirror Microsoft SQL Server images only.
      name =
          name.replace(
              "mcr.microsoft.com/mssql/server:", "registry.ddbuild.io/images/mirror/sqlserver:");
    }

    return DockerImageName.parse(name);
  }

  @Override
  protected String getDescription() {
    return "Image name substitutor to load images from registry.ddbuild.io";
  }
}
