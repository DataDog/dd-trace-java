package datadog.trace.civisibility.config;

import com.squareup.moshi.Json;
import java.util.Collections;
import java.util.Set;

public class ChangedFiles {

  public static final ChangedFiles EMPTY = new ChangedFiles(null, Collections.emptySet());

  @Json(name = "base_sha")
  private final String baseSha;

  private final Set<String> files;

  ChangedFiles(String baseSha, Set<String> files) {
    this.baseSha = baseSha;
    this.files = files;
  }

  public String getBaseSha() {
    return baseSha;
  }

  public Set<String> getFiles() {
    return files;
  }
}
