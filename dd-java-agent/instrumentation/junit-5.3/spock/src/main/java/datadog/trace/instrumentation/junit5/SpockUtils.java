package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.config.SkippableTest;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.spockframework.runtime.SpockNode;
import org.spockframework.runtime.model.FeatureMetadata;
import org.spockframework.runtime.model.SpecElementInfo;

public class SpockUtils {

  private static final MethodHandle GET_TEST_TAGS;
  private static final MethodHandle GET_TEST_TAG_VALUE;

  static {
    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
    ClassLoader defaultClassLoader = ClassLoaderUtils.getDefaultClassLoader();
    GET_TEST_TAGS = accessGetTestTags(lookup, defaultClassLoader);
    GET_TEST_TAG_VALUE = accessGetTestTagValue(lookup, defaultClassLoader);
  }

  private static MethodHandle accessGetTestTags(
      MethodHandles.Lookup lookup, ClassLoader classLoader) {
    try {
      Class<?> testTaggable =
          classLoader.loadClass("org.spockframework.runtime.model.ITestTaggable");
      Method method = testTaggable.getDeclaredMethod("getTestTags");
      return lookup.unreflect(method);
    } catch (Throwable throwable) {
      return null;
    }
  }

  private static MethodHandle accessGetTestTagValue(
      MethodHandles.Lookup lookup, ClassLoader classLoader) {
    try {
      Class<?> testTaggable = classLoader.loadClass("org.spockframework.runtime.model.TestTag");
      Method method = testTaggable.getDeclaredMethod("getValue");
      return lookup.unreflect(method);
    } catch (Throwable throwable) {
      return null;
    }
  }

  /*
   * ITestTaggable and TestTag classes are accessed via reflection
   * since they're available starting with Spock 2.2,
   * and we support Spock 2.0
   */
  public static Collection<TestTag> getTags(SpockNode<?> spockNode) {
    try {
      Collection<TestTag> junitPlatformTestTags = new ArrayList<>();
      SpecElementInfo<?, ?> nodeInfo = spockNode.getNodeInfo();
      Collection<?> testTags = (Collection<?>) GET_TEST_TAGS.invoke(nodeInfo);
      for (Object testTag : testTags) {
        String tagValue = (String) GET_TEST_TAG_VALUE.invoke(testTag);
        TestTag junitPlatformTestTag = TestTag.create(tagValue);
        junitPlatformTestTags.add(junitPlatformTestTag);
      }
      return junitPlatformTestTags;

    } catch (Throwable throwable) {
      // ignore
      return Collections.emptyList();
    }
  }

  public static Method getTestMethod(MethodSource methodSource) {
    String methodName = methodSource.getMethodName();
    if (methodName == null) {
      return null;
    }

    Class<?> testClass = methodSource.getJavaClass();
    if (testClass == null) {
      return null;
    }

    try {
      for (Method declaredMethod : testClass.getDeclaredMethods()) {
        FeatureMetadata featureMetadata = declaredMethod.getAnnotation(FeatureMetadata.class);
        if (featureMetadata == null) {
          continue;
        }

        if (methodName.equals(featureMetadata.name())) {
          return declaredMethod;
        }
      }

    } catch (Throwable e) {
      // ignore
    }
    return null;
  }

  public static SkippableTest toSkippableTest(SpockNode spockNode) {
    TestSource testSource = spockNode.getSource().orElse(null);
    if (testSource instanceof MethodSource) {
      MethodSource methodSource = (MethodSource) testSource;
      String testSuiteName = methodSource.getClassName();
      String displayName = spockNode.getDisplayName();
      String testParameters = JUnitPlatformUtils.getParameters(methodSource, displayName);
      return new SkippableTest(testSuiteName, displayName, testParameters, null);

    } else {
      return null;
    }
  }

  public static boolean isSpec(TestDescriptor testDescriptor) {
    UniqueId uniqueId = testDescriptor.getUniqueId();
    List<UniqueId.Segment> segments = uniqueId.getSegments();
    UniqueId.Segment lastSegment = segments.get(segments.size() - 1);
    return "spec".equals(lastSegment.getType());
  }
}
