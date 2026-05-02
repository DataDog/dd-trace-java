package datadog.trace.agent.tooling.muzzle;

import datadog.trace.agent.tooling.bytebuddy.SharedTypePools;
import datadog.trace.agent.tooling.muzzle.Reference.Mismatch;
import datadog.trace.api.Pair;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;

/** Matches a set of references against a classloader. */
public class ReferenceMatcher {
  public static final ReferenceMatcher NO_REFERENCES = new ReferenceMatcher();

  private final Reference[] references;

  private ReferenceProvider referenceProvider;

  public ReferenceMatcher(final Reference... references) {
    this.references = references;
  }

  public ReferenceMatcher withReferenceProvider(ReferenceProvider referenceProvider) {
    if (this != NO_REFERENCES) {
      this.referenceProvider = referenceProvider;
    }
    return this;
  }

  public Reference[] getReferences() {
    return references;
  }

  /**
   * Matcher used by ByteBuddy, fails-fast at first mismatch found.
   *
   * @param loader Classloader to validate against (or null for bootstrap)
   * @return true if all references match the classpath of loader
   */
  public boolean matches(ClassLoader loader) {
    List<Mismatch> mismatches = new ArrayList<>();
    TypePool typePool = SharedTypePools.typePool(loader);
    for (Reference reference : references) {
      if (!checkReference(typePool, reference, loader, mismatches)) {
        return false;
      }
    }
    if (null != referenceProvider) {
      for (Reference reference : referenceProvider.buildReferences(typePool)) {
        if (!checkReference(typePool, reference, loader, mismatches)) {
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
    List<Mismatch> mismatches = new ArrayList<>();
    TypePool typePool = SharedTypePools.typePool(loader);
    for (Reference reference : references) {
      checkReference(typePool, reference, loader, mismatches);
    }
    if (null != referenceProvider) {
      for (Reference reference : referenceProvider.buildReferences(typePool)) {
        checkReference(typePool, reference, loader, mismatches);
      }
    }
    return mismatches;
  }

  /**
   * Check a reference against a classloader's classpath.
   *
   * @return A list of mismatched sources. A list of size 0 means the reference matches the class.
   */
  private static boolean checkReference(
      TypePool typePool, Reference reference, ClassLoader loader, List<Mismatch> mismatches) {
    int previousMismatchCount = mismatches.size();
    if (checkMatch(typePool, reference, loader, mismatches)) {
      return true;
    }
    if (reference instanceof OrReference) {
      for (Reference or : ((OrReference) reference).ors) {
        if (checkReference(typePool, or, loader, mismatches)) {
          // alternative spec matched, remove the original spec's mismatches
          mismatches.subList(previousMismatchCount, mismatches.size()).clear();
          return true;
        }
      }
    }
    return false;
  }

  @SuppressForbidden
  private static boolean checkMatch(
      TypePool typePool, Reference reference, ClassLoader loader, List<Mismatch> mismatches) {
    try {
      final TypePool.Resolution resolution = typePool.describe(reference.className);
      if (!resolution.isResolved()) {
        mismatches.add(new Mismatch.MissingClass(reference.sources, reference.className));
        return false;
      }
      return checkMatch(reference, resolution.resolve(), mismatches);
    } catch (final Exception e) {
      String message = e.getMessage();
      if (message != null && message.startsWith("Cannot resolve type description for ")) {
        // bytebuddy throws an illegal state exception with this message if it cannot resolve types
        // TODO: handle missing type resolutions without catching bytebuddy's exceptions
        final String className = message.replace("Cannot resolve type description for ", "");
        mismatches.add(new Mismatch.MissingClass(reference.sources, className));
      } else {
        // Shouldn't happen. Fail the reference check and add a mismatch for debug logging.
        mismatches.add(
            new Mismatch.ReferenceCheckError(
                e, reference, null != loader ? loader.toString() : "<bootstrap>"));
      }
      return false;
    }
  }

  public static boolean checkMatch(
      final Reference reference,
      final TypeDescription typeOnClasspath,
      final List<Mismatch> mismatches) {
    int previousMismatchCount = mismatches.size();

    if (!Reference.matches(reference.flags, typeOnClasspath.getModifiers())) {
      final String desc = reference.className;
      mismatches.add(
          new Mismatch.MissingFlag(
              reference.sources, desc, reference.flags, typeOnClasspath.getModifiers()));
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
    Map<Pair<String, String>, Reference.Method> indexedMethods = indexMethods(reference.methods);
    Map<Pair<String, String>, Reference.Field> indexedFields = indexFields(reference.fields);
    traverseHierarchy(reference, typeOnClasspath, indexedMethods, indexedFields, mismatches);
    if (!indexedMethods.isEmpty()) {
      findInterfaceMethods(
          reference, typeOnClasspath, indexedMethods, mismatches, new HashSet<TypeDescription>());
    }

    for (Reference.Field missingField : indexedFields.values()) {
      mismatches.add(
          new Reference.Mismatch.MissingField(
              missingField.sources,
              reference.className,
              missingField.name,
              missingField.fieldType));
    }
    for (Reference.Method missingMethod : indexedMethods.values()) {
      mismatches.add(
          new Reference.Mismatch.MissingMethod(
              missingMethod.sources,
              reference.className,
              missingMethod.name,
              missingMethod.methodType));
    }

    return previousMismatchCount == mismatches.size();
  }

  private static Map<Pair<String, String>, Reference.Field> indexFields(
      final Reference.Field[] fields) {
    Map<Pair<String, String>, Reference.Field> map = new HashMap<>(fields.length * 4 / 3);
    for (Reference.Field field : fields) {
      map.put(Pair.of(field.name, field.fieldType), field);
    }
    return map;
  }

  private static Map<Pair<String, String>, Reference.Method> indexMethods(
      final Reference.Method[] methods) {
    Map<Pair<String, String>, Reference.Method> map = new HashMap<>(methods.length * 4 / 3);
    for (Reference.Method method : methods) {
      map.put(Pair.of(method.name, method.methodType), method);
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
        String descriptor = fieldType.getType().asErasure().getDescriptor();
        Pair<String, String> key = Pair.of(fieldType.getInternalName(), descriptor);
        Reference.Field found = fieldsToFind.remove(key);
        if (null != found) {
          if (!Reference.matches(found.flags, fieldType.getModifiers())) {
            final String desc = reference.className + "#" + found.name + found.fieldType;
            flagMismatches.add(
                new Mismatch.MissingFlag(
                    found.sources, desc, found.flags, fieldType.getModifiers()));
            break;
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
      final List<Reference.Mismatch> flagMismatches,
      final Set<TypeDescription> visitedInterfaces) {
    if (!methodsToFind.isEmpty()) {
      for (final TypeDescription.Generic interfaceType : typeOnClasspath.getInterfaces()) {
        TypeDescription erasureType = interfaceType.asErasure();
        findMethodsForType(reference, erasureType, methodsToFind, flagMismatches);
        if (methodsToFind.isEmpty()) {
          break;
        }
        if (visitedInterfaces.add(erasureType)) {
          findInterfaceMethods(
              reference, erasureType, methodsToFind, flagMismatches, visitedInterfaces);
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
          if (!Reference.matches(found.flags, methodDescription.getModifiers())) {
            final String desc = reference.className + "#" + found.name + found.methodType;
            flagMismatches.add(
                new Mismatch.MissingFlag(
                    found.sources, desc, found.flags, methodDescription.getModifiers()));
            break;
          }
        }
        if (methodsToFind.isEmpty()) {
          break;
        }
      }
    }
  }
}
