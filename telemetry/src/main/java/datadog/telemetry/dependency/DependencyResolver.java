package datadog.telemetry.dependency;

import datadog.trace.util.Strings;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
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

public class DependencyResolver {

  private static final Logger log = LoggerFactory.getLogger(DependencyResolver.class);

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

  public static boolean isValidURL(final URL url) {
    if (url == null) {
      return false;
    }
    final String path = url.toString();
    if (path == null) {
      return false;
    }
    if (path.endsWith(".class")) {
      // Cannot resolve dependencies for individual classes.
      return false;
    }
    final String protocol = url.getProtocol();
    if ("file".equals(protocol)) {
      return true;
    }
    if ("jar".equals(protocol)) {
      return path.startsWith("jar:file:");
    }
    return false;
  }

  public static List<Dependency> fromURL(final URL url) {
    if (!isValidURL(url)) {
      return Collections.emptyList();
    }
    final String protocol = url.getProtocol();
    try {
      if ("file".equals(protocol)) {
        return fromJar(new File(url.getFile()));
      } else if ("jar".equals(protocol)) {
        final Dependency dependency = fromNestedJar(url);
        if (dependency != null) {
          return Collections.singletonList(dependency);
        }
      }
    } catch (Throwable t) {
      log.debug("Failed to determine dependency for {}", url, t);
    }

    return Collections.emptyList();
  }

  private static List<Dependency> fromJar(final File jar) {
    if (!jar.exists()) {
      log.debug("unable to find dependency {} (path does not exist)", jar);
      return Collections.emptyList();
    }

    try (JarFile file = new JarFile(jar, false /* no verify */)) {
      final List<Dependency> dependencies = fromMavenPom(file);
      if (!dependencies.isEmpty()) {
        return dependencies;
      }

      // Try to guess from manifest or file name
      try (InputStream is = Files.newInputStream(jar.toPath())) {
        final Manifest manifest = file.getManifest();
        return Collections.singletonList(guessFallbackNoPom(manifest, jar.getName(), is));
      }
    } catch (IOException e) {
      log.debug("unable to read jar file {}", jar, e);
    }

    return Collections.emptyList();
  }

  /* for jar urls as handled by spring boot */
  static Dependency fromNestedJar(final URL url) {
    String lastPart = null;
    String fileName = null;
    try {
      JarURLConnection jarConnection = (JarURLConnection) url.openConnection();
      Manifest manifest = jarConnection.getManifest();

      JarFile jarFile = jarConnection.getJarFile();

      // the !/ separator is hardcoded into JarURLConnection class
      String jarFileName = jarFile.getName();
      int posSep = jarFileName.indexOf("!/");
      if (posSep == -1) {
        log.debug("Unable to guess nested dependency for uri '{}': '!/' not found", url);
        return null;
      }
      lastPart = jarFileName.substring(posSep + 1);
      fileName = lastPart.substring(lastPart.lastIndexOf("/") + 1);

      return guessFallbackNoPom(manifest, fileName, jarConnection.getInputStream());
    } catch (Exception e) {
      log.debug("unable to open nested jar manifest for {}", url, e);
    }
    log.debug(
        "Unable to guess nested dependency for uri '{}', lastPart: '{}', fileName: '{}'",
        url,
        lastPart,
        fileName);
    return null;
  }

  private static List<Dependency> fromMavenPom(final JarFile jar) {
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

  static synchronized Dependency guessFallbackNoPom(
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
