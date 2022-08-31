package datadog.telemetry.dependency;

import datadog.trace.util.Strings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.UndeclaredThrowableException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Dependency {

  private static final Logger log = LoggerFactory.getLogger(Dependency.class);

  private static final Pattern FILE_REGEX =
      Pattern.compile("(.+)-(\\d[^/-]+(?:-(?:\\w+))*)?\\.jar$");

  private static final byte[] buf = new byte[8192];

  private final String name;
  private final String version;
  private final String source;
  private final String hash;

  Dependency(String name, String version, String source) {
    this(name, version, source, null);
  }

  Dependency(String name, String version, String source, String hash) {
    this.name = checkNotNull(name);
    this.version = checkNotNull(version);
    this.source = checkNotNull(source);
    this.hash = hash;
  }

  private static <T> T checkNotNull(T val) {
    if (val == null) {
      throw new NullPointerException("Expected arg to be non-null");
    }
    return val;
  }

  public String getName() {
    return name;
  }

  public String getVersion() {
    return version;
  }

  public String getSource() {
    return source;
  }

  public String getHash() {
    return hash;
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
            dependencies.add(new Dependency(name, version, (new File(jar.getName())).getName()));
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
    String hash;

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
    } else if (isValidArtifactId(implementationVersion)) {
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

    // Compute hash for all dependencies that has no pom
    // No reliable version calculate hash and use any version
    MessageDigest md;
    try {
      md = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      // should not happen
      throw new UndeclaredThrowableException(e);
    }
    is = new DigestInputStream(is, md);
    while (is.read(buf, 0, buf.length) > 0) {}
    hash = String.format("%040X", new BigInteger(1, md.digest()));

    return new Dependency(name, version, source, hash);
  }

  /** Check is string is valid artifactId. Should be a non-capital single word. */
  private static boolean isValidArtifactId(String artifactId) {
    return artifactId != null
        && !artifactId.contains(" ")
        && !artifactId.contains(".")
        && !Character.isUpperCase(artifactId.charAt(0));
  }

  /** Check is string is valid groupId. Should be a non-capital plural-word separated with dot. */
  private static boolean isValidGroupId(String group) {
    return group != null
        && !group.contains(" ")
        && group.contains(".")
        && !Character.isUpperCase(group.charAt(0));
  }

  private static boolean equalsNonNull(String s1, String s2) {
    return s1 != null && s1.equals(s2);
  }

  private static String parseGroupId(String bundleSymbolicName, String bundleName) {
    // Usually bundleSymbolicName contains bundleName at the end. Check this

    // Bundle name can contains dash, so normalize it
    String normalizedBundleName = Strings.replace(bundleName, "-", ".");

    String bundleNameWithPrefix = "." + normalizedBundleName;
    if (bundleSymbolicName.endsWith(bundleNameWithPrefix)) {
      int truncateLen = bundleSymbolicName.length() - bundleNameWithPrefix.length();
      return bundleSymbolicName.substring(0, truncateLen);
    }

    return null;
  }
}
