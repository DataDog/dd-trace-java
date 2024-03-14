package datadog.trace.instrumentation.junit5;

import datadog.trace.api.civisibility.InstrumentationBridge;
import datadog.trace.api.civisibility.config.TestIdentifier;
import java.lang.invoke.MethodHandle;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spockframework.runtime.SpockNode;
import org.spockframework.runtime.model.FeatureInfo;
import org.spockframework.runtime.model.FeatureMetadata;
import org.spockframework.runtime.model.SpecElementInfo;

public class SpockUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(SpockUtils.class);

  private static final datadog.trace.util.MethodHandles METHOD_HANDLES =
      new datadog.trace.util.MethodHandles(ClassLoaderUtils.getDefaultClassLoader());

  private static final MethodHandle GET_TEST_TAGS =
      METHOD_HANDLES.method("org.spockframework.runtime.model.ITestTaggable", "getTestTags");

  private static final MethodHandle GET_TEST_TAG_VALUE =
      METHOD_HANDLES.method("org.spockframework.runtime.model.TestTag", "getValue");

  static {
    TestIdentifierFactory.register("spock", SpockUtils::toTestIdentifier);
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
      if (!(nodeInfo instanceof FeatureInfo)) {
        return Collections.emptyList();
      }

      Collection<?> testTags = (Collection<?>) GET_TEST_TAGS.invoke(nodeInfo);
      for (Object testTag : testTags) {
        String tagValue = (String) GET_TEST_TAG_VALUE.invoke(testTag);
        TestTag junitPlatformTestTag = TestTag.create(tagValue);
        junitPlatformTestTags.add(junitPlatformTestTag);
      }
      return junitPlatformTestTags;

    } catch (Throwable throwable) {
      LOGGER.warn("Could not get tags from a spock node", throwable);
      return Collections.emptyList();
    }
  }

  public static boolean isUnskippable(SpockNode<?> spockNode) {
    Collection<TestTag> tags = SpockUtils.getTags(spockNode);
    for (TestTag tag : tags) {
      if (InstrumentationBridge.ITR_UNSKIPPABLE_TAG.equals(tag.getName())) {
        return true;
      }
    }
    return false;
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
      LOGGER.warn("Could not get test method from method source", e);
    }
    return null;
  }

  public static TestIdentifier toTestIdentifier(TestDescriptor testDescriptor) {
    TestSource testSource = testDescriptor.getSource().orElse(null);
    if (testSource instanceof MethodSource && testDescriptor instanceof SpockNode) {
      SpockNode spockNode = (SpockNode) testDescriptor;
      MethodSource methodSource = (MethodSource) testSource;
      String testSuiteName = methodSource.getClassName();
      String displayName = spockNode.getDisplayName();
      String testParameters = JUnitPlatformUtils.getParameters(methodSource, displayName);
      return new TestIdentifier(testSuiteName, displayName, testParameters, null);

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

  public static TestDescriptor getSpecDescriptor(TestDescriptor testDescriptor) {
    while (testDescriptor != null && !isSpec(testDescriptor)) {
      testDescriptor = testDescriptor.getParent().orElse(null);
    }
    return testDescriptor;
  }
}
