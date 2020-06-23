package datadog.trace.agent.tooling.muzzle;

import static net.bytebuddy.dynamic.loading.ClassLoadingStrategy.BOOTSTRAP_LOADER;

import datadog.trace.agent.tooling.AgentTooling;
import datadog.trace.agent.tooling.Utils;
import datadog.trace.agent.tooling.muzzle.Reference.Mismatch;
import datadog.trace.agent.tooling.muzzle.Reference.Source;
import datadog.trace.bootstrap.WeakCache;
import datadog.trace.bootstrap.instrumentation.api.Pair;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;

/** Matches a set of references against a classloader. */
@Slf4j
public final class ReferenceMatcher {
  private final WeakCache<ClassLoader, Boolean> mismatchCache = AgentTooling.newWeakCache();
  private final Reference[] references;
  private final Set<String> helperClassNames;

  public ReferenceMatcher(final Reference... references) {
    this(new String[0], references);
  }

  public ReferenceMatcher(final String[] helperClassNames, final Reference[] references) {
    this.references = references;
    this.helperClassNames = new HashSet<>(Arrays.asList(helperClassNames));
  }

  public Reference[] getReferences() {
    return references;
  }

  /**
   * Matcher used by ByteBuddy. Fails fast and only caches empty results, or complete results
   *
   * @param loader Classloader to validate against (or null for bootstrap)
   * @return true if all references match the classpath of loader
   */
  public boolean matches(ClassLoader loader) {
    if (loader == BOOTSTRAP_LOADER) {
      loader = Utils.getBootstrapProxy();
    }
    final ClassLoader cl = loader;
    return mismatchCache.getIfPresentOrCompute(
        loader,
        new Callable<Boolean>() {
          @Override
          public Boolean call() {
            return doesMatch(cl);
          }
        });
  }

  private boolean doesMatch(final ClassLoader loader) {
    final List<Mismatch> mismatches = new ArrayList<>();
    for (final Reference reference : references) {
      // Don't reference-check helper classes.
      // They will be injected by the instrumentation's HelperInjector.
      if (!helperClassNames.contains(reference.getClassName())) {
        if (!checkMatch(reference, loader, mismatches)) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Loads the full list of mismatches. Used in debug contexts only
   *
   * @param loader Classloader to validate against (or null for bootstrap)
   * @return A list of all mismatches between this ReferenceMatcher and loader's classpath.
   */
  public List<Reference.Mismatch> getMismatchedReferenceSources(ClassLoader loader) {
    if (loader == BOOTSTRAP_LOADER) {
      loader = Utils.getBootstrapProxy();
    }
    List<Mismatch> mismatches = new ArrayList<>();
    for (final Reference reference : references) {
      // Don't reference-check helper classes.
      // They will be injected by the instrumentation's HelperInjector.
      if (!helperClassNames.contains(reference.getClassName())) {
        checkMatch(reference, loader, mismatches);
      }
    }

    return mismatches;
  }

  /**
   * Check a reference against a classloader's classpath.
   *
   * @param loader
   * @return A list of mismatched sources. A list of size 0 means the reference matches the class.
   */
  private static boolean checkMatch(
      final Reference reference, final ClassLoader loader, final List<Mismatch> mismatches) {
    final TypePool typePool =
        AgentTooling.poolStrategy()
            .typePool(AgentTooling.locationStrategy().classFileLocator(loader), loader);
    try {
      final TypePool.Resolution resolution = typePool.describe(reference.getClassName());
      if (!resolution.isResolved()) {
        mismatches.add(
            new Mismatch.MissingClass(
                reference.getSources().toArray(new Source[0]), reference.getClassName()));
        return false;
      }
      return checkMatch(reference, resolution.resolve(), mismatches);
    } catch (final Exception e) {
      if (e.getMessage().startsWith("Cannot resolve type description for ")) {
        // bytebuddy throws an illegal state exception with this message if it cannot resolve types
        // TODO: handle missing type resolutions without catching bytebuddy's exceptions
        final String className = e.getMessage().replace("Cannot resolve type description for ", "");
        mismatches.add(
            new Mismatch.MissingClass(reference.getSources().toArray(new Source[0]), className));
        return false;
      } else {
        // Shouldn't happen. Fail the reference check and add a mismatch for debug logging.
        mismatches.add(new Mismatch.ReferenceCheckError(e, reference, loader));
        return false;
      }
    }
  }

  public static boolean checkMatch(
      final Reference reference,
      final TypeDescription typeOnClasspath,
      final List<Mismatch> mismatches) {

    for (final Reference.Flag flag : reference.getFlags()) {
      if (!flag.matches(typeOnClasspath.getModifiers())) {
        final String desc = reference.getClassName();
        mismatches.add(
            new Mismatch.MissingFlag(
                reference.getSources().toArray(new Source[0]),
                desc,
                flag,
                typeOnClasspath.getModifiers()));
      }
    }

    // we match the fields and methods we are looking for by name, type or descriptor, and flags.
    // So that we don't have to check every field/method on every type we visit against every
    // field/method we're looking for, we index them by name and type/descriptor first.
    // When we find a possible match, we'll remove the item, so stop looking for it,
    // but will check the flags there and add a mismatch there and then.
    // Once every field/method in a type is checked, if there is a super class, we visit it,
    // and check each field/type there, and continue recursively. As soon as either map is empty,
    // we stop looking for elements (fields/methods) of that type, otherwise every concrete type
    // in the hierarchy is visited. Finally, once concrete types have been checked,
    // since the base type implements any supertype's interfaces too,
    // if we have entries in indexedMethods (i.e. there are still some missing methods),
    // the interfaces will be checked once. If either map is non empty by the end, mismatches are
    // constructed.
    //
    // This means:
    // * each field/method in the type hierarchy will be checked at most once
    // * each type in the hierarchy will be visited at most once
    Map<Pair<String, String>, Reference.Method> indexedMethods =
        indexMethods(reference.getMethods());
    Map<Pair<String, String>, Reference.Field> indexedFields = indexFields(reference.getFields());
    traverseHierarchy(reference, typeOnClasspath, indexedMethods, indexedFields, mismatches);
    if (!indexedMethods.isEmpty()) {
      findInterfaceMethods(reference, typeOnClasspath, indexedMethods, mismatches);
    }

    for (Reference.Field missingField : indexedFields.values()) {
      mismatches.add(
          new Reference.Mismatch.MissingField(
              missingField.getSources().toArray(new Reference.Source[0]),
              reference.getClassName(),
              missingField.getName(),
              missingField.getType().getInternalName()));
    }
    for (Reference.Method missingMethod : indexedMethods.values()) {
      mismatches.add(
          new Reference.Mismatch.MissingMethod(
              missingMethod.getSources().toArray(new Reference.Source[0]),
              missingMethod.getName(),
              missingMethod.getDescriptor()));
    }

    return mismatches.isEmpty();
  }

  private static Map<Pair<String, String>, Reference.Field> indexFields(
      final Set<Reference.Field> fields) {
    Map<Pair<String, String>, Reference.Field> map = new HashMap<>(fields.size() * 4 / 3);
    for (Reference.Field field : fields) {
      map.put(Pair.of(field.getName(), field.getType().getInternalName()), field);
    }
    return map;
  }

  private static Map<Pair<String, String>, Reference.Method> indexMethods(
      final Set<Reference.Method> methods) {
    Map<Pair<String, String>, Reference.Method> map = new HashMap<>(methods.size() * 4 / 3);
    for (Reference.Method method : methods) {
      map.put(Pair.of(method.getName(), method.getDescriptor()), method);
    }
    return map;
  }

  private static void traverseHierarchy(
      final Reference reference,
      final TypeDescription typeOnClasspath,
      Map<Pair<String, String>, Reference.Method> methodsToFind,
      Map<Pair<String, String>, Reference.Field> fieldsToFind,
      final List<Reference.Mismatch> flagMismatches) {
    findFieldsForType(reference, typeOnClasspath, fieldsToFind, flagMismatches);
    findMethodsForType(reference, typeOnClasspath, methodsToFind, flagMismatches);
    if (!fieldsToFind.isEmpty() || !methodsToFind.isEmpty()) {
      TypeDescription.Generic superClass = typeOnClasspath.getSuperClass();
      if (superClass != null) {
        traverseHierarchy(
            reference, superClass.asErasure(), methodsToFind, fieldsToFind, flagMismatches);
      }
    }
  }

  private static void findFieldsForType(
      final Reference reference,
      final TypeDescription typeOnClasspath,
      final Map<Pair<String, String>, Reference.Field> fieldsToFind,
      final List<Reference.Mismatch> flagMismatches) {
    if (!fieldsToFind.isEmpty()) {
      for (final FieldDescription.InDefinedShape fieldType : typeOnClasspath.getDeclaredFields()) {
        Pair<String, String> key =
            Pair.of(fieldType.getInternalName(), fieldType.getType().asErasure().getInternalName());
        Reference.Field found = fieldsToFind.remove(key);
        if (null != found) {
          for (final Reference.Flag flag : found.getFlags()) {
            if (!flag.matches(fieldType.getModifiers())) {
              final String desc =
                  reference.getClassName()
                      + "#"
                      + found.getName()
                      + found.getType().getInternalName();
              flagMismatches.add(
                  new Mismatch.MissingFlag(
                      found.getSources().toArray(new Source[0]),
                      desc,
                      flag,
                      fieldType.getModifiers()));
              break;
            }
          }
        }
        if (fieldsToFind.isEmpty()) {
          break;
        }
      }
    }
  }

  private static void findInterfaceMethods(
      final Reference reference,
      final TypeDescription typeOnClasspath,
      final Map<Pair<String, String>, Reference.Method> methodsToFind,
      final List<Reference.Mismatch> flagMismatches) {
    if (!methodsToFind.isEmpty()) {
      for (final TypeDescription.Generic interfaceType : typeOnClasspath.getInterfaces()) {
        findMethodsForType(reference, interfaceType.asErasure(), methodsToFind, flagMismatches);
        if (methodsToFind.isEmpty()) {
          break;
        }
      }
    }
  }

  private static void findMethodsForType(
      final Reference reference,
      final TypeDescription typeOnClasspath,
      final Map<Pair<String, String>, Reference.Method> methodsToFind,
      final List<Reference.Mismatch> flagMismatches) {
    if (!methodsToFind.isEmpty()) {
      for (final MethodDescription.InDefinedShape methodDescription :
          typeOnClasspath.getDeclaredMethods()) {
        Pair<String, String> key =
            Pair.of(methodDescription.getInternalName(), methodDescription.getDescriptor());
        Reference.Method found = methodsToFind.remove(key);
        if (null != found) {
          // will stop looking for this one now, but check it has the right flags
          for (final Reference.Flag flag : found.getFlags()) {
            if (!flag.matches(methodDescription.getModifiers())) {
              final String desc =
                  reference.getClassName() + "#" + found.getName() + found.getDescriptor();
              flagMismatches.add(
                  new Mismatch.MissingFlag(
                      found.getSources().toArray(new Source[0]),
                      desc,
                      flag,
                      methodDescription.getModifiers()));
              break;
            }
          }
        }
        if (methodsToFind.isEmpty()) {
          break;
        }
      }
    }
  }
}
