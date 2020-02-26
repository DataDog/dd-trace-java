package datadog.trace.agent.tooling.bytebuddy.matcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HasInterfaceNamed.safeGetSuperClass;

import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ExtendsClassNamed <T extends TypeDescription>
  extends ElementMatcher.Junction.AbstractBase<T> {

  // TODO: take into account classloaders
  private static final Map<String, Set<String>> typeCache = new HashMap<>();

  private final String className;

  public ExtendsClassNamed(String className) {
    this.className = className;
  }

  @Override
  public boolean matches(T target) {
    if (target.isInterface()) {
      return false;
    }
    Set<String> cachedSupers = typeCache.get(target.getTypeName());
    if (cachedSupers != null) {
      return cachedSupers.contains(className);
    }

    Deque<TypeDefinition> hierarchyStack = new ArrayDeque<>();
    hierarchyStack.add(target);
    Set<String> hierarchySupers = new HashSet<>();
    TypeDefinition typeDefinition = safeGetSuperClass(target);
    while (typeDefinition != null) {
      Set<String> currentHierarchySupers = typeCache.get(typeDefinition.getTypeName());
      if (currentHierarchySupers != null) {
        hierarchySupers.addAll(currentHierarchySupers);
        break;
      }
      hierarchyStack.add(typeDefinition);
      typeDefinition = safeGetSuperClass(typeDefinition);
    }
    TypeDefinition typeDef;
    while ((typeDef = hierarchyStack.pollLast()) != null) {
      Set<String> levelSupers = typeCache.get(typeDef.getTypeName());
      if (levelSupers != null) {
        hierarchySupers.addAll(levelSupers);
        continue;
      }
      TypeDescription.Generic superClass = typeDef.getSuperClass();
      if (superClass != null) {
          hierarchySupers.add(superClass.getTypeName());
      }
      // put in cache all interfaces from this level and super levels
      typeCache.put(typeDef.getTypeName(), new HashSet<String>(hierarchySupers));
    }


    return false;
  }
}
