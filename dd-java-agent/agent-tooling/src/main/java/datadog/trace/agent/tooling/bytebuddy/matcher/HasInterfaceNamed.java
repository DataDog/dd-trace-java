package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.safeTypeDefinitionName;

import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.build.HashCodeAndEqualsPlugin;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.description.type.TypeList;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Replaces not(isInterface()).and(hasInterface(named("org.hibernate.Criteria")))
 * @param <T> The type of the matched entity.
 */
@Slf4j
@HashCodeAndEqualsPlugin.Enhance
class HasInterfaceNamed <T extends TypeDescription>
  extends ElementMatcher.Junction.AbstractBase<T> {

  // TODO: take into account classloaders
  private static final Map<String, Set<String>> typeCache = new HashMap<>();

  private final String interfaceName;

  public HasInterfaceNamed(String interfaceName) {
    this.interfaceName = interfaceName;
  }

  @Override
  public boolean matches(T target) {
    if (target.isInterface()) { // not interested of interface as target, need a concrete type
      return false;
    }
    Set<String> cachedInterfaces = typeCache.get(target.getTypeName());
    if (cachedInterfaces != null) {
      return cachedInterfaces.contains(interfaceName);
    }
    Deque<TypeDefinition> hierarchyStack = new ArrayDeque<>();
    hierarchyStack.add(target);
    Set<String> hierarchyIntfs = new HashSet<>();
    TypeDefinition typeDefinition = safeGetSuperClass(target);
    while (typeDefinition != null) {
      Set<String> currentHierarchyIntfs = typeCache.get(typeDefinition.getTypeName());
      if (currentHierarchyIntfs != null) {
        hierarchyIntfs.addAll(currentHierarchyIntfs);
        break;
      }
      hierarchyStack.add(typeDefinition);
      typeDefinition = safeGetSuperClass(typeDefinition);
    }
    TypeDefinition typeDef;
    while ((typeDef = hierarchyStack.pollLast()) != null) {
      Set<String> levelIntfs = typeCache.get(typeDef.getTypeName());
      if (levelIntfs != null) {
        hierarchyIntfs.addAll(levelIntfs);
        continue;
      }
      TypeList.Generic intfList = typeDef.getInterfaces();
      if (intfList != null) {
        for (int i = 0; i < intfList.size(); i++) {
          TypeDescription.Generic intf = intfList.get(i);
          if (intf == null)
            continue;
          hierarchyIntfs.add(intf.asErasure().getTypeName());
        }
      }
      // put in cache all interfaces from this level and super levels
      typeCache.put(typeDef.getTypeName(), new HashSet<String>(hierarchyIntfs));
    }
    return false;
  }

  static TypeDefinition safeGetSuperClass(final TypeDefinition typeDefinition) {
    try {
      return typeDefinition.getSuperClass();
    } catch (final Exception e) {
      log.debug(
        "{} trying to get super class for target {}: {}",
        e.getClass().getSimpleName(),
        safeTypeDefinitionName(typeDefinition),
        e.getMessage());
      return null;
    }
  }

  @Override
  public String toString() {
    return "hasInterfaceNamed(" + interfaceName + ")";
  }

}
