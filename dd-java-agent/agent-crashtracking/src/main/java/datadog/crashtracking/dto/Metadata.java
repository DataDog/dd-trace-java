package datadog.crashtracking.dto;

import com.squareup.moshi.Json;
import java.util.Arrays;
import java.util.Objects;

public final class Metadata {
  @Json(name = "library_name")
  public final String libraryName;

  @Json(name = "library_version")
  public final String libraryVersion;

  public final String family;
  public final String[] tags;

  public Metadata(String libraryName, String libraryVersion, String family, String[] tags) {
    this.libraryName = libraryName;
    this.libraryVersion = libraryVersion;
    this.family = family;
    this.tags = tags;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Metadata metadata = (Metadata) o;
    return Objects.equals(libraryName, metadata.libraryName)
        && Objects.equals(libraryVersion, metadata.libraryVersion)
        && Objects.equals(family, metadata.family)
        && Arrays.equals(tags, metadata.tags);
  }

  @Override
  public int hashCode() {
    return Objects.hash(libraryName, libraryVersion, family, Arrays.hashCode(tags));
  }
}
