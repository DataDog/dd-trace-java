package datadog.trace.instrumentation.junit5;

import datadog.trace.instrumentation.junit5.execution.RetryDescriptorFactory;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.util.function.UnaryOperator;
import org.junit.platform.commons.util.ClassLoaderUtils;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.spockframework.runtime.IterationNode;
import org.spockframework.runtime.SimpleFeatureNode;
import org.spockframework.runtime.model.FeatureInfo;
import org.spockframework.runtime.model.IterationInfo;
import spock.config.RunnerConfiguration;

/**
 * Reconstructs the Spock retry descriptor through its own constructor (with a transformed unique
 * id) instead of cloning + mutating the final {@code uniqueId} field to avoid JEP 500 warnings.
 *
 * <p>The leaf descriptor reaching the retry advice is either a {@link SimpleFeatureNode}
 * (non-parametric feature, on every supported tag) or an {@link IterationNode} (a parametric {@code
 * where:} row).
 */
public final class SpockRetryDescriptorFactory implements RetryDescriptorFactory {

  private static final MethodHandles METHOD_HANDLES =
      new MethodHandles(ClassLoaderUtils.getDefaultClassLoader());

  private static final MethodHandle SIMPLE_FEATURE_NODE_CONSTRUCTOR =
      METHOD_HANDLES.constructor(
          SimpleFeatureNode.class,
          UniqueId.class,
          RunnerConfiguration.class,
          FeatureInfo.class,
          IterationNode.class);

  private static final MethodHandle ITERATION_NODE_CONSTRUCTOR =
      METHOD_HANDLES.constructor(
          IterationNode.class, UniqueId.class, RunnerConfiguration.class, IterationInfo.class);

  private static final MethodHandle SIMPLE_FEATURE_NODE_DELEGATE =
      METHOD_HANDLES.privateFieldGetter(SimpleFeatureNode.class, "delegate");

  private static final MethodHandle ITERATION_NODE_INFO =
      METHOD_HANDLES.privateFieldGetter(IterationNode.class, "iterationInfo");

  @Override
  public TestDescriptor copy(TestDescriptor original, UnaryOperator<UniqueId> idTransform) {
    if (original instanceof SimpleFeatureNode) {
      return copySimpleFeatureNode((SimpleFeatureNode) original, idTransform);
    }
    if (original instanceof IterationNode) {
      return copyIterationNode((IterationNode) original, idTransform);
    }
    return null; // unknown Spock node type -> fall back to the generic clone
  }

  private static TestDescriptor copySimpleFeatureNode(
      SimpleFeatureNode original, UnaryOperator<UniqueId> idTransform) {
    if (SIMPLE_FEATURE_NODE_CONSTRUCTOR == null || ITERATION_NODE_CONSTRUCTOR == null) {
      return null;
    }
    RunnerConfiguration configuration = original.getConfiguration();
    FeatureInfo featureInfo = original.getNodeInfo();
    IterationNode originalDelegate = METHOD_HANDLES.invoke(SIMPLE_FEATURE_NODE_DELEGATE, original);
    if (originalDelegate == null) {
      return null;
    }
    IterationInfo iterationInfo = METHOD_HANDLES.invoke(ITERATION_NODE_INFO, originalDelegate);

    UniqueId newId = idTransform.apply(original.getUniqueId());
    // keep the delegate a proper child of the copy and distinct across attempts
    UniqueId.Segment delegateSegment = originalDelegate.getUniqueId().getLastSegment();
    UniqueId newDelegateId = newId.append(delegateSegment.getType(), delegateSegment.getValue());

    IterationNode delegate =
        METHOD_HANDLES.invoke(
            ITERATION_NODE_CONSTRUCTOR, newDelegateId, configuration, iterationInfo);
    if (delegate == null) {
      return null;
    }
    return METHOD_HANDLES.invoke(
        SIMPLE_FEATURE_NODE_CONSTRUCTOR, newId, configuration, featureInfo, delegate);
  }

  private static TestDescriptor copyIterationNode(
      IterationNode original, UnaryOperator<UniqueId> idTransform) {
    if (ITERATION_NODE_CONSTRUCTOR == null) {
      return null;
    }
    RunnerConfiguration configuration = original.getConfiguration();
    IterationInfo iterationInfo = METHOD_HANDLES.invoke(ITERATION_NODE_INFO, original);
    if (iterationInfo == null) {
      return null;
    }
    UniqueId newId = idTransform.apply(original.getUniqueId());
    return METHOD_HANDLES.invoke(ITERATION_NODE_CONSTRUCTOR, newId, configuration, iterationInfo);
  }
}
