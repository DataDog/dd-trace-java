package datadog.telemetry.dependency;

import datadog.trace.util.Strings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Dependency {

  private static final Logger log = LoggerFactory.getLogger(Dependency.class);

  private static final Pattern FILE_REGEX =
      Pattern.compile("(.+)-(\\d[^/-]+(?:-(?:\\w+))*)?\\.jar$");

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

  public static List<Dependency> fromMavenPom(JarFile jar) {
    if (jar == null) {
      return Collections.emptyList();
    }
    List<Dependency> dependencies = new ArrayList<>(1);
    Enumeration<JarEntry> entries = jar.entries();
    while (entries.hasMoreElements()) {
      JarEntry jarEntry = entries.nextElement();
      String filename = jarEntry.getName();
      if (filename.endsWith("pom.properties")) {
        try (InputStream is = jar.getInputStream(jarEntry)) {
          if (is == null) {
            return Collections.emptyList();
          }
          Properties properties = new Properties();
          properties.load(is);
          String groupId = properties.getProperty("groupId");
          String artifactId = properties.getProperty("artifactId");
          String version = properties.getProperty("version");
          String name = groupId + ":" + artifactId;

          if (groupId == null || artifactId == null || version == null) {
            log.debug(
                "'pom.properties' does not have all the required properties: "
                    + "jar={}, entry={}, groupId={}, artifactId={}, version={}",
                jar.getName(),
                jarEntry.getName(),
                groupId,
                artifactId,
                version);
          } else {
            log.debug(
                "dependency found in pom.properties: "
                    + "jar={}, entry={}, groupId={}, artifactId={}, version={}",
                jar.getName(),
                jarEntry.getName(),
                groupId,
                artifactId,
                version);
            dependencies.add(
                new Dependency(name, version, new File(jar.getName()).getName(), null));
          }
        } catch (IOException e) {
          log.debug("unable to read 'pom.properties' file from {}", jar.getName(), e);
          return Collections.emptyList();
        }
      }
    }
    return dependencies;
  }

  public static synchronized Dependency guessFallbackNoPom(
      Manifest manifest, String source, InputStream is) throws IOException {
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
      Attributes mainAttributes = manifest.getMainAttributes();
      bundleSymbolicName = mainAttributes.getValue("bundle-symbolicname");
      bundleName = mainAttributes.getValue("bundle-name");
      bundleVersion = mainAttributes.getValue("bundle-version");
      implementationTitle = mainAttributes.getValue("implementation-title");
      implementationVersion = mainAttributes.getValue("implementation-version");
    }

    // Guess from file name
    String fileNameArtifact = null;
    String fileNameVersion = null;
    Matcher m = FILE_REGEX.matcher(source);
    if (m.matches()) {
      fileNameArtifact = m.group(1);
      fileNameVersion = m.group(2);
    } else {
      // name without the version?
      int idx = source.lastIndexOf('.');
      if (idx > 0) {
        String nameOnly = source.substring(0, idx); // name without the extension
        if (isValidArtifactId(nameOnly)) {
          fileNameArtifact = nameOnly;
        }
      }
    }

    // Find for the most suitable name (based on priority)
    if (isValidArtifactId(bundleSymbolicName)) {
      artifactId = bundleSymbolicName;
    } else if (isValidArtifactId(bundleName)) {
      artifactId = bundleName;
    } else if (isValidArtifactId(implementationTitle)) {
      artifactId = implementationTitle;
    } else if (fileNameArtifact != null) {
      artifactId = fileNameArtifact;
    } else {
      artifactId = bundleSymbolicName;
    }

    // Try to get groupId from bundleSymbolicName and bundleName
    if (isValidGroupId(bundleSymbolicName) && isValidArtifactId(bundleName)) {
      groupId = parseGroupId(bundleSymbolicName, artifactId);
      artifactId = bundleName;
    }

    // Find version string only if any 2 variables are equal
    if (equalsNonNull(bundleVersion, implementationVersion)) {
      version = bundleVersion;
    } else if (equalsNonNull(implementationVersion, fileNameVersion)) {
      version = implementationVersion;
    } else if (equalsNonNull(bundleVersion, fileNameVersion)) {
      version = fileNameVersion;
    } else {
      artifactId = source;

      if (implementationVersion != null) {
        version = implementationVersion;
      } else if (fileNameVersion != null) {
        version = fileNameVersion;
      } else if (bundleVersion != null) {
        version = bundleVersion;
      } else {
        version = "";
      }
    }

    String name;
    if (groupId != null) {
      name = groupId + ":" + artifactId;
    } else {
      // no group resolved. use only artifactId
      name = artifactId;
    }

    if (md != null) {
      // Compute hash for all dependencies that has no pom
      // No reliable version calculate hash and use any version
      md.reset();
      is = new DigestInputStream(is, md);
      while (is.read(buf, 0, buf.length) > 0) {}
      hash = String.format("%040X", new BigInteger(1, md.digest()));
    }
    log.debug("No maven dependency added {}.{} jar name {} hash {}", name, version, source, hash);
    return new Dependency(name, version, source, hash);
  }

  /** Check is string is valid artifactId. Should be a non-capital single word. */
  private static boolean isValidArtifactId(String artifactId) {
    return hasText(artifactId)
        && !artifactId.contains(" ")
        && !artifactId.contains(".")
        && !Character.isUpperCase(artifactId.charAt(0));
  }

  /** Check is string is valid groupId. Should be a non-capital plural-word separated with dot. */
  private static boolean isValidGroupId(String group) {
    return hasText(group)
        && !group.contains(" ")
        && group.contains(".")
        && !Character.isUpperCase(group.charAt(0));
  }

  private static boolean hasText(final String value) {
    return value != null && !value.isEmpty();
  }

  private static boolean equalsNonNull(String s1, String s2) {
    return s1 != null && s1.equals(s2);
  }

  private static String parseGroupId(String bundleSymbolicName, String bundleName) {
    // Usually bundleSymbolicName contains bundleName at the end. Check this

    // Bundle name can contain dash, so normalize it
    String normalizedBundleName = Strings.replace(bundleName, "-", ".");

    String bundleNameWithPrefix = "." + normalizedBundleName;
    if (bundleSymbolicName.endsWith(bundleNameWithPrefix)) {
      int truncateLen = bundleSymbolicName.length() - bundleNameWithPrefix.length();
      return bundleSymbolicName.substring(0, truncateLen);
    }

    return null;
  }
}
