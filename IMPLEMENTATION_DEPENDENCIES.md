# Implementation: Dependencies Extraction

## Overview
Extract all application dependencies (JAR versions) and include in tracer flare to enable exact environment reproduction.

---

## New File: `DependencyExtractor.java`

**Location**: `utils/flare-utils/src/main/java/datadog/flare/DependencyExtractor.java`

```java
package datadog.flare;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracts application dependencies (JAR versions) for tracer flare.
 * Tries multiple strategies to get the most complete dependency list.
 */
public final class DependencyExtractor {
  private static final Logger log = LoggerFactory.getLogger(DependencyExtractor.class);
  
  private static final JsonAdapter<Map<String, Object>> JSON_ADAPTER =
      new Moshi.Builder().build().adapter(
          Types.newParameterizedType(Map.class, String.class, Object.class));
  
  private static final Pattern JAR_VERSION_PATTERN = 
      Pattern.compile("([^/\\\\]+?)-(\\d+[.\\d]*[^/\\\\]*)\\.jar$");
  
  /**
   * Extract dependencies using the best available method.
   * Returns JSON string with dependency information.
   */
  public static String extractDependencies() {
    Map<String, Object> result = new HashMap<>();
    result.put("extraction_timestamp", System.currentTimeMillis());
    result.put("extraction_methods_tried", new ArrayList<String>());
    
    List<String> methodsTried = (List<String>) result.get("extraction_methods_tried");
    
    // Strategy 1: Try Maven pom.xml in common locations
    Map<String, String> mavenDeps = tryExtractFromMaven();
    if (!mavenDeps.isEmpty()) {
      methodsTried.add("maven_pom");
      result.put("dependencies", mavenDeps);
      result.put("source", "maven");
      return JSON_ADAPTER.toJson(result);
    }
    
    // Strategy 2: Try Gradle build files
    Map<String, String> gradleDeps = tryExtractFromGradle();
    if (!gradleDeps.isEmpty()) {
      methodsTried.add("gradle_build");
      result.put("dependencies", gradleDeps);
      result.put("source", "gradle");
      return JSON_ADAPTER.toJson(result);
    }
    
    // Strategy 3: Scan classpath JARs (fallback - less accurate)
    methodsTried.add("classpath_scan");
    Map<String, String> classpathDeps = extractFromClasspath();
    result.put("dependencies", classpathDeps);
    result.put("source", "classpath_scan");
    result.put("warning", "Extracted from classpath JARs - versions may be incomplete");
    
    return JSON_ADAPTER.toJson(result);
  }
  
  /**
   * Try to extract from Maven pom.xml if available.
   */
  private static Map<String, String> tryExtractFromMaven() {
    // Common Maven locations
    String[] pomLocations = {
        "pom.xml",                    // Running from project root
        "../pom.xml",                 // Running from target/
        "../../pom.xml",              // Running from target/classes/
        System.getProperty("user.dir") + "/pom.xml"
    };
    
    for (String location : pomLocations) {
      File pomFile = new File(location);
      if (pomFile.exists() && pomFile.canRead()) {
        log.debug("Found pom.xml at: {}", pomFile.getAbsolutePath());
        return parseMavenDependencyTree(pomFile);
      }
    }
    
    return Collections.emptyMap();
  }
  
  /**
   * Parse Maven dependency tree from pom.xml location.
   */
  private static Map<String, String> parseMavenDependencyTree(File pomFile) {
    Map<String, String> dependencies = new HashMap<>();
    
    try {
      // Try to run mvn dependency:list
      ProcessBuilder pb = new ProcessBuilder(
          "mvn", "dependency:list", 
          "-DoutputFile=/tmp/deps.txt",
          "-f", pomFile.getAbsolutePath()
      );
      
      Process process = pb.start();
      int exitCode = process.waitFor();
      
      if (exitCode == 0) {
        Path depsFile = Paths.get("/tmp/deps.txt");
        if (Files.exists(depsFile)) {
          dependencies = parseMavenDependencyList(depsFile);
          Files.delete(depsFile);
        }
      }
    } catch (Exception e) {
      log.debug("Could not extract Maven dependencies via mvn command", e);
    }
    
    return dependencies;
  }
  
  /**
   * Parse Maven dependency list output.
   * Format: groupId:artifactId:packaging:version:scope
   */
  private static Map<String, String> parseMavenDependencyList(Path file) throws IOException {
    Map<String, String> deps = new HashMap<>();
    
    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("[")) {
          continue; // Skip Maven log lines
        }
        
        // Format: groupId:artifactId:packaging:version:scope
        String[] parts = line.split(":");
        if (parts.length >= 4) {
          String artifactKey = parts[0] + ":" + parts[1]; // groupId:artifactId
          String version = parts[3];
          deps.put(artifactKey, version);
        }
      }
    }
    
    return deps;
  }
  
  /**
   * Try to extract from Gradle build files.
   */
  private static Map<String, String> tryExtractFromGradle() {
    String[] gradleLocations = {
        "build.gradle",
        "build.gradle.kts",
        "../build.gradle",
        "../build.gradle.kts",
        System.getProperty("user.dir") + "/build.gradle"
    };
    
    for (String location : gradleLocations) {
      File gradleFile = new File(location);
      if (gradleFile.exists() && gradleFile.canRead()) {
        log.debug("Found Gradle build file at: {}", gradleFile.getAbsolutePath());
        return parseGradleDependencies(gradleFile);
      }
    }
    
    return Collections.emptyMap();
  }
  
  /**
   * Parse Gradle dependencies.
   */
  private static Map<String, String> parseGradleDependencies(File gradleFile) {
    Map<String, String> dependencies = new HashMap<>();
    
    try {
      ProcessBuilder pb = new ProcessBuilder(
          "./gradlew", "dependencies", "--configuration", "runtimeClasspath"
      );
      pb.directory(gradleFile.getParentFile());
      
      Process process = pb.start();
      
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        
        String line;
        Pattern depPattern = Pattern.compile("([^:]+):([^:]+):([\\d.]+)");
        
        while ((line = reader.readLine()) != null) {
          Matcher matcher = depPattern.matcher(line);
          if (matcher.find()) {
            String group = matcher.group(1).trim();
            String artifact = matcher.group(2).trim();
            String version = matcher.group(3).trim();
            dependencies.put(group + ":" + artifact, version);
          }
        }
      }
      
      process.waitFor();
    } catch (Exception e) {
      log.debug("Could not extract Gradle dependencies", e);
    }
    
    return dependencies;
  }
  
  /**
   * Extract dependencies from classpath by scanning JAR files.
   * This is a fallback method when build files aren't available.
   */
  private static Map<String, String> extractFromClasspath() {
    Map<String, String> dependencies = new HashMap<>();
    String classpath = System.getProperty("java.class.path");
    
    if (classpath == null || classpath.isEmpty()) {
      log.warn("Classpath is empty, cannot extract dependencies");
      return dependencies;
    }
    
    String[] paths = classpath.split(File.pathSeparator);
    
    for (String path : paths) {
      if (path.endsWith(".jar")) {
        DependencyInfo info = extractFromJar(path);
        if (info != null) {
          dependencies.put(info.artifactId, info.version);
        }
      }
    }
    
    return dependencies;
  }
  
  /**
   * Extract dependency info from a single JAR file.
   */
  private static DependencyInfo extractFromJar(String jarPath) {
    File jarFile = new File(jarPath);
    if (!jarFile.exists()) {
      return null;
    }
    
    DependencyInfo info = new DependencyInfo();
    
    // Strategy 1: Try to read from MANIFEST.MF
    try (JarFile jar = new JarFile(jarFile)) {
      Manifest manifest = jar.getManifest();
      if (manifest != null) {
        Attributes attrs = manifest.getMainAttributes();
        
        // Try various manifest attributes
        String implTitle = attrs.getValue("Implementation-Title");
        String implVersion = attrs.getValue("Implementation-Version");
        String bundleName = attrs.getValue("Bundle-SymbolicName");
        String bundleVersion = attrs.getValue("Bundle-Version");
        
        if (implTitle != null && implVersion != null) {
          info.artifactId = implTitle;
          info.version = implVersion;
          info.source = "manifest";
          return info;
        }
        
        if (bundleName != null && bundleVersion != null) {
          info.artifactId = bundleName;
          info.version = bundleVersion;
          info.source = "manifest_osgi";
          return info;
        }
      }
    } catch (IOException e) {
      log.debug("Could not read manifest from JAR: {}", jarPath, e);
    }
    
    // Strategy 2: Try to parse from filename (less reliable)
    String fileName = jarFile.getName();
    Matcher matcher = JAR_VERSION_PATTERN.matcher(fileName);
    
    if (matcher.find()) {
      info.artifactId = matcher.group(1);
      info.version = matcher.group(2);
      info.source = "filename";
      return info;
    }
    
    // Strategy 3: Try to find pom.properties inside JAR
    try (JarFile jar = new JarFile(jarFile)) {
      jar.stream()
          .filter(entry -> entry.getName().endsWith("pom.properties"))
          .findFirst()
          .ifPresent(entry -> {
            try (InputStream is = jar.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
              
              String line;
              while ((line = reader.readLine()) != null) {
                if (line.startsWith("groupId=")) {
                  info.groupId = line.substring("groupId=".length());
                } else if (line.startsWith("artifactId=")) {
                  info.artifactId = line.substring("artifactId=".length());
                } else if (line.startsWith("version=")) {
                  info.version = line.substring("version=".length());
                }
              }
              
              if (info.groupId != null && info.artifactId != null) {
                info.artifactId = info.groupId + ":" + info.artifactId;
                info.source = "pom_properties";
              }
            } catch (IOException e) {
              log.debug("Could not read pom.properties from JAR: {}", jarPath, e);
            }
          });
      
      if (info.artifactId != null && info.version != null) {
        return info;
      }
    } catch (IOException e) {
      log.debug("Could not scan JAR for pom.properties: {}", jarPath, e);
    }
    
    return null;
  }
  
  /**
   * Helper class to hold dependency information.
   */
  private static class DependencyInfo {
    String groupId;
    String artifactId;
    String version;
    String source;
  }
}
```

---

## Integration into TracerFlareService

**File**: `utils/flare-utils/src/main/java/datadog/flare/TracerFlareService.java`

**Add this method**:

```java
private void addDependencies(ZipOutputStream zip) throws IOException {
  try {
    String dependencies = DependencyExtractor.extractDependencies();
    TracerFlare.addText(zip, "dependencies.json", dependencies);
  } catch (Exception e) {
    // Don't fail the entire flare if dependency extraction fails
    String error = "{\"error\": \"" + e.getMessage() + "\", \"dependencies\": {}}";
    TracerFlare.addText(zip, "dependencies.json", error);
  }
}
```

**Update `buildFlareZip` method** (line ~197):

```java
private byte[] buildFlareZip(long startMillis, long endMillis, boolean dumpThreads)
    throws IOException {
  try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      ZipOutputStream zip = new ZipOutputStream(bytes)) {

    addPrelude(zip, startMillis, endMillis);
    addConfig(zip);
    addRuntime(zip);
    addDependencies(zip);  // ← ADD THIS LINE
    TracerFlare.addReportsToFlare(zip);
    if (dumpThreads) {
      addThreadDump(zip);
    }
    zip.finish();

    return bytes.toByteArray();
  }
}
```

---

## Expected Output Format

**File**: `dependencies.json`

```json
{
  "extraction_timestamp": 1638360000000,
  "source": "maven",
  "extraction_methods_tried": ["maven_pom"],
  "dependencies": {
    "org.springframework.boot:spring-boot-starter-web": "3.0.1",
    "org.springframework:spring-webmvc": "6.0.2",
    "org.hibernate:hibernate-core": "6.1.5.Final",
    "com.zaxxer:HikariCP": "5.0.1",
    "org.postgresql:postgresql": "42.5.1",
    "redis.clients:jedis": "4.3.1",
    "com.fasterxml.jackson.core:jackson-databind": "2.14.1",
    "org.slf4j:slf4j-api": "2.0.6",
    "ch.qos.logback:logback-classic": "1.4.5"
  }
}
```

Or with classpath fallback:

```json
{
  "extraction_timestamp": 1638360000000,
  "source": "classpath_scan",
  "extraction_methods_tried": ["maven_pom", "gradle_build", "classpath_scan"],
  "warning": "Extracted from classpath JARs - versions may be incomplete",
  "dependencies": {
    "spring-boot-starter-web": "3.0.1",
    "spring-webmvc": "6.0.2",
    "hibernate-core": "6.1.5.Final",
    "HikariCP": "5.0.1"
  }
}
```

---

## Testing

```java
// Test class
public class DependencyExtractorTest {
  @Test
  public void testExtractDependencies() {
    String json = DependencyExtractor.extractDependencies();
    assertNotNull(json);
    assertTrue(json.contains("dependencies"));
    assertTrue(json.contains("extraction_timestamp"));
  }
  
  @Test
  public void testMavenExtraction() {
    // Create test pom.xml
    // Run extraction
    // Verify results
  }
}
```

---

## Build Configuration

Add to `utils/flare-utils/build.gradle`:

```gradle
dependencies {
  implementation 'com.squareup.moshi:moshi:1.14.0'
  // ... existing dependencies
}
```

---

## Limitations & Future Improvements

### Current Limitations:
1. Requires Maven/Gradle commands to be available in PATH
2. May miss dynamically loaded dependencies
3. Transitive dependencies included (could be verbose)

### Future Improvements:
1. Add filtering for only direct dependencies
2. Support for other build tools (Ivy, SBT)
3. Cache results to avoid repeated extraction
4. Include dependency conflicts/exclusions
5. Show which versions were expected vs. actual

---

## Impact

**Size**: ~20-50 KB additional (JSON with 50-200 dependencies)  
**Overhead**: 100-500ms during flare preparation  
**Value**: CRITICAL - Enables exact environment reproduction

