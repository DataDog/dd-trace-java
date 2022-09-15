package datadog.trace.agent.tooling.bytebuddy.csi;

import static datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool.CONSTANT_INTERFACE_METHODREF_TAG;
import static datadog.trace.agent.tooling.bytebuddy.csi.ConstantPool.CONSTANT_METHODREF_TAG;
import static datadog.trace.agent.tooling.csi.CallSiteAdvice.HasFlags.COMPUTE_MAX_STACK;

import datadog.trace.agent.tooling.bytebuddy.ClassFileLocators;
import datadog.trace.agent.tooling.csi.CallSiteAdvice;
import datadog.trace.agent.tooling.csi.Pointcut;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.jar.asm.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class holding a collection of {@link CallSiteAdvice} indexed by the declaring type, method name
 * and method description, it stores the collection of helper classes required by the advices.
 */
public class Advices {

  private static final Logger LOG = LoggerFactory.getLogger(Advices.class);

  public static final Advices EMPTY =
      new Advices(
          Collections.<String, Map<String, Map<String, CallSiteAdvice>>>emptyMap(),
          new String[0],
          0,
          AdviceIntrospector.NoOpAdviceInstrospector.INSTANCE) {
        @Override
        public boolean isEmpty() {
          return true;
        }
      };

  private final Map<String, Map<String, Map<String, CallSiteAdvice>>> advices;

  private final String[] helpers;

  private final AdviceIntrospector introspector;

  private final int flags;

  private Advices(
      final Map<String, Map<String, Map<String, CallSiteAdvice>>> advices,
      final String[] helpers,
      final int flags,
      final AdviceIntrospector introspector) {
    this.advices = Collections.unmodifiableMap(advices);
    this.helpers = helpers;
    this.flags = flags;
    this.introspector = introspector;
  }

  public static Advices fromCallSites(@Nonnull final CallSiteAdvice... advices) {
    return fromCallSites(Arrays.asList(advices));
  }

  public static Advices fromCallSites(@Nonnull final Iterable<CallSiteAdvice> advices) {
    return fromCallSites(advices, AdviceIntrospector.ConstantPoolInstrospector.INSTANCE);
  }

  public static Advices fromCallSites(
      @Nonnull final Iterable<CallSiteAdvice> advices,
      @Nonnull final AdviceIntrospector introspector) {
    final Map<String, Map<String, Map<String, CallSiteAdvice>>> adviceMap = new HashMap<>();
    final Set<String> helperSet = new HashSet<>();
    int flags = 0;
    for (final CallSiteAdvice advice : advices) {
      flags |= addAdvice(adviceMap, helperSet, advice);
    }
    return adviceMap.isEmpty()
        ? EMPTY
        : new Advices(adviceMap, helperSet.toArray(new String[0]), flags, introspector);
  }

  private static int addAdvice(
      @Nonnull final Map<String, Map<String, Map<String, CallSiteAdvice>>> advices,
      @Nonnull final Set<String> helpers,
      @Nonnull final CallSiteAdvice advice) {
    final Pointcut pointcut = advice.pointcut();
    Map<String, Map<String, CallSiteAdvice>> typeAdvices = advices.get(pointcut.type());
    if (typeAdvices == null) {
      typeAdvices = new HashMap<>();
      advices.put(pointcut.type(), typeAdvices);
    }
    Map<String, CallSiteAdvice> methodAdvices = typeAdvices.get(pointcut.method());
    if (methodAdvices == null) {
      methodAdvices = new HashMap<>();
      typeAdvices.put(pointcut.method(), methodAdvices);
    }
    final CallSiteAdvice oldAdvice = methodAdvices.put(pointcut.descriptor(), advice);
    if (oldAdvice != null) {
      throw new UnsupportedOperationException(
          String.format(
              "Advice %s and %s match the same pointcut, this is not yet supported",
              oldAdvice, advice));
    }
    if (advice instanceof CallSiteAdvice.HasHelpers) {
      final String[] helperClassNames = ((CallSiteAdvice.HasHelpers) advice).helperClassNames();
      if (helperClassNames != null) {
        Collections.addAll(helpers, helperClassNames);
      }
    }
    return advice instanceof CallSiteAdvice.HasFlags
        ? ((CallSiteAdvice.HasFlags) advice).flags()
        : 0;
  }

  /**
   * The method will try to discover the {@link CallSiteAdvice} that should be applied to a specific
   * type by using the assigned {@link AdviceIntrospector}
   */
  public Advices findAdvices(@Nonnull final TypeDescription type, final ClassLoader loader) {
    if (advices.isEmpty()) {
      return this;
    }
    return introspector.findAdvices(this, type, loader);
  }

  // used for testing
  public CallSiteAdvice findAdvice(@Nonnull final Pointcut pointcut) {
    return findAdvice(pointcut.type(), pointcut.method(), pointcut.descriptor());
  }

  public CallSiteAdvice findAdvice(@Nonnull final Handle handle) {
    return findAdvice(handle.getOwner(), handle.getName(), handle.getDesc());
  }

  /**
   * The method tries to find the configured advice for the specific method call
   *
   * @param callee type owning the method
   * @param method method name
   * @param descriptor descriptor of the method
   * @return the advice or {@code null} if none found
   */
  public CallSiteAdvice findAdvice(
      @Nonnull final String callee,
      @Nonnull final String method,
      @Nonnull final String descriptor) {
    if (advices.isEmpty()) {
      return null;
    }
    final Map<String, Map<String, CallSiteAdvice>> typeAdvices = advices.get(callee);
    if (typeAdvices == null) {
      return null;
    }
    final Map<String, CallSiteAdvice> methodAdvices = typeAdvices.get(method);
    if (methodAdvices == null) {
      return null;
    }
    return methodAdvices.get(descriptor);
  }

  public String[] getHelpers() {
    return helpers;
  }

  public boolean isEmpty() {
    return advices.isEmpty();
  }

  public boolean hasFlag(final int flag) {
    return (this.flags & flag) > 0;
  }

  public boolean computeMaxStack() {
    return hasFlag(COMPUTE_MAX_STACK);
  }

  /**
   * Instance of this class will try to discover the advices required to instrument a class before
   * visiting it
   */
  public interface AdviceIntrospector {

    @Nonnull
    Advices findAdvices(
        @Nonnull final Advices advices,
        @Nonnull final TypeDescription type,
        final ClassLoader classLoader);

    class NoOpAdviceInstrospector implements AdviceIntrospector {

      public static final AdviceIntrospector INSTANCE = new NoOpAdviceInstrospector();

      @Override
      public @Nonnull Advices findAdvices(
          final @Nonnull Advices advices,
          final @Nonnull TypeDescription type,
          final ClassLoader classLoader) {
        return advices;
      }
    }

    /**
     * This class will try to parse the constant pool of the class in order to discover if any
     * configured advices should be applied.
     */
    class ConstantPoolInstrospector implements AdviceIntrospector {

      public static final AdviceIntrospector INSTANCE = new ConstantPoolInstrospector();

      private static final Map<Integer, ConstantPoolHandler> CP_HANDLERS;

      static {
        final Map<Integer, ConstantPoolHandler> handlers = new HashMap<>(4);
        final ConstantPoolHandler methodRefHandler = new MethodRefHandler();
        handlers.put(CONSTANT_METHODREF_TAG, methodRefHandler);
        handlers.put(CONSTANT_INTERFACE_METHODREF_TAG, methodRefHandler);
        CP_HANDLERS = Collections.unmodifiableMap(handlers);
      }

      @Override
      public @Nonnull Advices findAdvices(
          final @Nonnull Advices advices,
          final @Nonnull TypeDescription type,
          final ClassLoader classLoader) {
        try (final ClassFileLocator classFile = ClassFileLocators.classFileLocator(classLoader)) {
          final ClassFileLocator.Resolution resolution = classFile.locate(type.getName());
          if (!resolution.isResolved()) {
            return advices; // cannot resolve class file so don't apply any filtering
          }
          final ConstantPool cp = new ConstantPool(resolution.resolve());
          for (int index = 1; index < cp.getCount(); index++) {
            final int referenceType = cp.getType(index);
            final ConstantPoolHandler handler = CP_HANDLERS.get(referenceType);
            // short circuit when any advice is found
            if (handler != null && handler.findAdvice(advices, cp, index) != null) {
              return advices;
            }
          }
          return EMPTY;
        } catch (final Throwable e) {
          if (LOG.isErrorEnabled()) {
            LOG.error(String.format("Failed to introspect %s constant pool", type), e);
          }
          return advices; // in case of error we should continue without filtering
        }
      }

      /** Handler for a particular type of constant pool type (MethodRef, InvokeDynamic, ...) */
      private interface ConstantPoolHandler {
        CallSiteAdvice findAdvice(@Nonnull Advices advices, @Nonnull ConstantPool cp, int index);
      }

      /**
       * Extracts advices by looking at method refs in the constant pool
       *
       * @see <a
       *     href="https://docs.oracle.com/javase/specs/jvms/se15/html/jvms-4.html#jvms-4.4.2">Methodref_info</a>
       */
      private static class MethodRefHandler implements ConstantPoolHandler {

        @Override
        public CallSiteAdvice findAdvice(
            @Nonnull final Advices advices, @Nonnull final ConstantPool cp, final int index) {
          final int offset = cp.getOffset(index);

          // u2 class_index;
          final int classIndex = cp.readUnsignedShort(offset);
          final int classNameIndex = cp.readUnsignedShort(cp.getOffset(classIndex));
          final String className = cp.readUTF8(cp.getOffset(classNameIndex));
          final Map<String, Map<String, CallSiteAdvice>> calleeAdvices =
              advices.advices.get(className);
          if (calleeAdvices == null) {
            return null;
          }

          // u2 name_and_type_index;
          final int nameAndTypeIndex = cp.readUnsignedShort(offset + 2);
          final int nameAndTypeOffset = cp.getOffset(nameAndTypeIndex);
          final int nameIndex = cp.readUnsignedShort(nameAndTypeOffset);
          final String name = cp.readUTF8(cp.getOffset(nameIndex));
          final Map<String, CallSiteAdvice> methodAdvices = calleeAdvices.get(name);
          if (methodAdvices == null) {
            return null;
          }
          final int descriptorIndex = cp.readUnsignedShort(nameAndTypeOffset + 2);
          final String descriptor = cp.readUTF8(cp.getOffset(descriptorIndex));
          return methodAdvices.get(descriptor);
        }
      }
    }
  }
}
