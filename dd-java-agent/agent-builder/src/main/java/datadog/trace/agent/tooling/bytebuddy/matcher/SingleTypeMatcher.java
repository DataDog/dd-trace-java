package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.security.ProtectionDomain;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;


public class SingleTypeMatcher extends ElementMatcher.Junction.ForNonNullValues<TypeDescription>
    implements AgentBuilder.RawMatcher {

  private final String name;

  public SingleTypeMatcher(final String name) {
    this.name = name;
  }

  //private static final Set<String> seen = Collections.synchronizedSet(new HashSet<String>());

  @Override
  protected boolean doMatch(TypeDescription target) {
    //if (target.getName().endsWith("v1.Subscriber") && name.equals(target.getName()))
    //  System.out.println("YES 0000000000000000000000000");
    //if (target.getName().endsWith("v1.Subscriber"))
    //  System.out.println("YES 1111111111111111111111111");
    //if (seen.add(target.getName()) && target.getName().contains("cloud"))
    //  System.out.println("SEEN ======================> " + target.getName());
    return name.equals(target.getName());
  }

  @Override
  public boolean matches(
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain) {
    return doMatch(typeDescription);
  }
}
