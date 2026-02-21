package test

import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.ImageNameSubstitutor

/**
 * A custom {@link ImageNameSubstitutor} implementation that rewrites Docker image names
 * to use Datadog’s internal registry {@code registry.ddbuild.io} when running in a CI environment.
 * <p>
 * Images from DockerHub already mirrored by {@code registry.ddbuild.io} via environment variable {@code TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX}
 * </p>
 *<p>
 * For images from other repositories custom image name substitutor should be implemented.
 * Internal registry is faster and not affected by rate limiting.
 * </p>
 */
class DataDogRegistryImageNameSubstitutor extends ImageNameSubstitutor {
  @Override
  DockerImageName apply(DockerImageName original) {
    def name = original.asCanonicalNameString()

    if (System.getenv('CI')) {
      // For now we need to mirror Microsoft SQL Server images only.
      name = name.replace(
        'mcr.microsoft.com/mssql/server:',
        'registry.ddbuild.io/images/mirror/sqlserver:'
        )
    }

    DockerImageName.parse(name)
  }

  @Override
  protected String getDescription() {
    'Image name substitutor to load images from registry.ddbuild.io'
  }
}
