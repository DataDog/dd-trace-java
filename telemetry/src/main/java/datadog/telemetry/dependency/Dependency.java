package datadog.telemetry.dependency;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Dependency {

  private static final Logger log = LoggerFactory.getLogger(Dependency.class);

  private static final Pattern FILE_REGEX =
      Pattern.compile("^(.+?)(?:-([0-9][^-]+(?:-\\w+)?))?\\.jar$");

  private static final byte[] buf = new byte[8192];

  private static final MessageDigest md;

  static {
    MessageDigest digest = null;
    try {
      digest = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      // should not happen
      log.error("Unable to create cipher", e);
    }
    md = digest;
  }

  public final String name;
  public final String version;
  public final String source;
  public final String hash;

  public Dependency(String name, String version, String source, @Nullable String hash) {
    this.name = name;
    this.version = version;
    this.source = source;
    this.hash = hash;
  }

  @Override
  public String toString() {
    return "Dependency{"
        + "name='"
        + name
        + '\''
        + ", version='"
        + version
        + '\''
        + ", source='"
        + source
        + '\''
        + ", hash='"
        + hash
        + '\''
        + '}';
  }

  public static List<Dependency> fromMavenPom(
      final String jar, Map<String, Properties> pomProperties) {
    if (pomProperties == null) {
      return Collections.emptyList();
    }
    List<Dependency> dependencies = new ArrayList<>(pomProperties.size());
    for (final Map.Entry<String, Properties> entry : pomProperties.entrySet()) {
      final Properties properties = entry.getValue();
      final String groupId = properties.getProperty("groupId");
      final String artifactId = properties.getProperty("artifactId");
      final String version = properties.getProperty("version");
      final String name = groupId + ":" + artifactId;

      if (groupId == null || artifactId == null || version == null) {
        log.debug(
            "pom.properties does not have all the required properties: "
                + "jar={}, entry={}, groupId={}, artifactId={}, version={}",
            jar,
            entry.getKey(),
            groupId,
            artifactId,
            version);
      } else {
        log.debug(
            "dependency found in pom.properties: "
                + "jar={}, entry={}, groupId={}, artifactId={}, version={}",
            jar,
            entry.getKey(),
            groupId,
            artifactId,
            version);
        dependencies.add(new Dependency(name, version, jar, null));
      }
    }
    return dependencies;
  }

  public static synchronized Dependency guessFallbackNoPom(
      Attributes manifest, String source, InputStream is) throws IOException {
    final int slashIndex = source.lastIndexOf('/');
    if (slashIndex >= 0) {
      source = source.substring(slashIndex + 1);
    }

    String artifactId;
    String groupId = null;
    String version;
    String hash = null;

    // Guess from manifest
    String bundleSymbolicName = null;
    String implementationTitle = null;
    String bundleName = null;
    String bundleVersion = null;
    String implementationVersion = null;
    if (manifest != null) {
      bundleSymbolicName = manifest.getValue("bundle-symbolicname");
      bundleName = manifest.getValue("bundle-name");
      bundleVersion = manifest.getValue("bundle-version");
      implementationTitle = manifest.getValue("implementation-title");
      implementationVersion = manifest.getValue("implementation-version");
    }

    // Guess from file name
    String fileNameArtifact = null;
    String fileNameVersion = null;
    Matcher m = FILE_REGEX.matcher(source);
    if (m.matches()) {
      fileNameArtifact = m.group(1);
      fileNameVersion = m.group(2);
    } else {
      // This code path should not be exercised right now, although it might in the future (e.g.
      // Knopflerfish uses
      // a jar storage without .jar extension). We used to strip the extension here, but if we
      // unexpectedly get here,
      // it would be better to get something informative even if weird. So just fallback to the full
      // name.
      fileNameArtifact = source;
    }

    // Find for the most suitable name (based on priority)
    if (isValidArtifactId(bundleName)) {
      artifactId = bundleName;
    } else if (isValidArtifactId(implementationTitle)) {
      artifactId = implementationTitle;
    } else {
      artifactId = fileNameArtifact;
    }

    // Bundle-Version and Implementation-Version have precedence only if they are equal.
    if (equalsNonNull(bundleVersion, implementationVersion)) {
      version = bundleVersion;
    } else if (hasText(fileNameVersion)) {
      version = fileNameVersion;
    } else if (hasText(bundleVersion)) {
      version = bundleVersion;
    } else if (hasText(implementationVersion)) {
      version = implementationVersion;
    } else {
      version = "";
    }

    // Try to get groupId from bundleSymbolicName and bundleName (or artifact id)
    if (hasText(bundleSymbolicName)) {
      groupId = parseGroupId(bundleSymbolicName, fileNameArtifact);
      if (!isValidGroupId(groupId)) {
        groupId = parseGroupId(bundleSymbolicName, bundleName);

        if (!isValidGroupId(groupId)) {
          groupId = null;
        }
      }
    }
    String name = artifactId;
    if (groupId != null) {
      name = groupId + ":" + artifactId;
    }

    if (md != null) {
      // Compute hash for all dependencies that have no pom
      // No reliable version calculate hash and use any version
      md.reset();
      is = new DigestInputStream(is, md);
      while (is.read(buf, 0, buf.length) > 0) {}
      hash = String.format("%040X", new BigInteger(1, md.digest()));
    }
    log.debug("No maven dependency added {}.{} jar name {} hash {}", name, version, source, hash);
    return new Dependency(name, version, source, hash);
  }

  private static boolean isValidArtifactId(String artifactId) {
    return hasText(artifactId)
        && !artifactId.contains(" ")
        && !artifactId.contains(".")
        && artifactId.toLowerCase(Locale.ROOT).equals(artifactId);
  }

  private static boolean isValidGroupId(String group) {
    return hasText(group)
        && !group.contains(" ")
        && group.contains(".")
        && group.toLowerCase(Locale.ROOT).equals(group);
  }

  private static boolean hasText(final String value) {
    return value != null && !value.isEmpty();
  }

  private static boolean equalsNonNull(String s1, String s2) {
    return s1 != null && s1.equals(s2);
  }

  private static String parseGroupId(String bundleSymbolicName, String bundleName) {
    // Some tools create Bundle-Symbolicname as `groupId.artifactid`.
    // We'll try to infer group id based on that.
    final String bundleNameWithPrefix = "." + bundleName;
    if (!bundleSymbolicName.endsWith(bundleNameWithPrefix)) {
      return null;
    }

    final int truncateLen = bundleSymbolicName.length() - bundleNameWithPrefix.length();
    final String groupId = bundleSymbolicName.substring(0, truncateLen);

    // Sometimes this will lead to an incorrect group id, in cases like com.opencsv / opencsv, which
    // are frequent.
    // This will trim both single word group ids, as well as prefixes suck as `uk.ac`.
    if (groupId.contains(".") && groupId.length() > 5) {
      return groupId;
    }

    return null;
  }
}
