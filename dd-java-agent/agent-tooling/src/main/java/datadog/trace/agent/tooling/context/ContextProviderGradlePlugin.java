package datadog.trace.agent.tooling.context;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.extendsClass;
import static net.bytebuddy.matcher.ElementMatchers.named;

import datadog.trace.agent.tooling.Instrumenter;
import java.io.File;
import java.util.Map;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.build.BuildLogger;
import net.bytebuddy.build.Plugin;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;

public class ContextProviderGradlePlugin extends Plugin.ForElementMatcher {
  private final ByteBuddy byteBuddy = new ByteBuddy();

  private final File targetFolder;
  private final BuildLogger logger;

  public ContextProviderGradlePlugin(final String targetFolder, final BuildLogger logger) {
    super(
        extendsClass(named(Instrumenter.Default.class.getName()))
            .and(ElementMatchers.isAnnotatedWith(named(ContextStoreDef.class.getName()))));

    this.targetFolder = new File(targetFolder);
    this.logger = logger;
  }

  @Override
  public DynamicType.Builder<?> apply(
      final DynamicType.Builder<?> builder,
      final TypeDescription typeDescription,
      final ClassFileLocator classFileLocator) {

    final ContextStoreDef contextStoreDef =
        typeDescription.getDeclaredAnnotations().ofType(ContextStoreDef.class).load();

    logger.info("Generating context store for " + typeDescription.getName());
    for (final ContextStoreMapping mapping : contextStoreDef.value()) {
      try {
        logger.info("Generating interface and context store for: " + mapping);
        final DynamicType.Unloaded<?> fieldAccessor =
            FieldBackedProvider.makeFieldAccessorInterface(
                byteBuddy, mapping.keyClass(), mapping.contextClass());
        final Map<TypeDescription, File> saved = fieldAccessor.saveIn(targetFolder);

        final TypeDescription fieldAccessorInterface = saved.keySet().iterator().next();
        final DynamicType.Unloaded<?> storeImplementation =
            FieldBackedProvider.makeContextStoreImplementationClass(
                byteBuddy, mapping.keyClass(), mapping.contextClass(), fieldAccessorInterface);
        storeImplementation.saveIn(targetFolder);

      } catch (final Exception e) {
        throw new RuntimeException("Unable to generate context store", e);
      }
    }

    return builder;
  }

  @Override
  public void close() {}
}
