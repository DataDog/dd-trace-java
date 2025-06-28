package datadog.crashtracking.dto;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.JsonWriter;
import com.squareup.moshi.ToJson;
import java.io.IOException;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SemanticVersion {
  private static final Pattern DOT_SPLITTER = Pattern.compile("\\.");

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
    String[] parts = DOT_SPLITTER.split(version);
    if (parts.length == 3) {
      return new SemanticVersion(
          safeParseInteger(parts[0]), safeParseInteger(parts[1]), safeParseInteger(parts[2]));
    } else if (parts.length == 2) {
      return new SemanticVersion(safeParseInteger(parts[0]), safeParseInteger(parts[1]), 0);
    } else if (parts.length == 1) {
      return new SemanticVersion(safeParseInteger(parts[0]), 0, 0);
    } else {
      throw new IllegalArgumentException("Invalid version string: " + version);
    }
  }

  private static final Pattern INTEGER_PATTERN = Pattern.compile("(\\d+).*");

  private static int safeParseInteger(String value) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      Matcher matcher = INTEGER_PATTERN.matcher(value);
      if (matcher.matches()) {
        // this is guaranteed to be an integer
        return Integer.parseInt(matcher.group(1));
      }
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
    return Objects.hash(major, minor, patch);
  }
}
