package datadog.crashtracking.dto;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.ToJson;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import datadog.trace.util.HashingUtils;

public final class SemanticVersion {
  private static final Pattern NUMERIC_SPLITTER = Pattern.compile("[^0-9]+");
  private static final Logger LOGGER = LoggerFactory.getLogger(SemanticVersion.class.getName());

  public static final class SemanticVersionAdapter {

    @ToJson
    public void toJson(JsonWriter writer, SemanticVersion version) throws IOException {
      writer.beginObject();
      writer.name("Semantic");
      writer.beginArray();
      writer.value(version.major);
      writer.value(version.minor);
      writer.value(version.patch);
      writer.endArray();
      writer.endObject();
    }

    @FromJson
    public SemanticVersion fromJson(JsonReader reader) throws IOException {
      reader.beginObject();
      String name = reader.nextName();
      if (!"Semantic".equals(name)) {
        throw new IOException("Expected 'Semantic' key");
      }
      reader.beginArray();
      int major = reader.nextInt();
      int minor = reader.nextInt();
      int patch = reader.nextInt();
      reader.endArray();
      reader.endObject();
      return new SemanticVersion(major, minor, patch);
    }
  }

  public final int major;
  public final int minor;
  public final int patch;

  public SemanticVersion(int major, int minor, int patch) {
    this.major = major;
    this.minor = minor;
    this.patch = patch;
  }

  public static SemanticVersion of(String version) {
    String[] parts = NUMERIC_SPLITTER.split(version, 4);
    if (parts.length == 0) {
      LOGGER.error("Invalid version string {} ", version);
      return new SemanticVersion(0, 0, 0); // have a sane default
    } else if (parts.length == 2) {
      return new SemanticVersion(safeParseInteger(parts[0]), safeParseInteger(parts[1]), 0);
    } else if (parts.length == 1) {
      return new SemanticVersion(safeParseInteger(parts[0]), 0, 0);
    }
    return new SemanticVersion(
        safeParseInteger(parts[0]), safeParseInteger(parts[1]), safeParseInteger(parts[2]));
  }

  private static int safeParseInteger(String value) {
    try {
      return Integer.parseInt(value);
    } catch (Throwable ignored) {
      return 0;
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SemanticVersion that = (SemanticVersion) o;
    return major == that.major && minor == that.minor && patch == that.patch;
  }

  @Override
  public int hashCode() {
    return HashingUtils.hash(major, minor, patch);
  }

  @Override
  public String toString() {
    return "SemanticVersion{" + "major=" + major + ", minor=" + minor + ", patch=" + patch + '}';
  }
}
