package datadog.trace.instrumentation.maven3;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.extendsClass;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.Set;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.codehaus.plexus.PlexusContainer;

@AutoService(Instrumenter.class)
public class MavenInstrumentation extends Instrumenter.CiVisibility
    implements Instrumenter.ForTypeHierarchy {

  public MavenInstrumentation() {
    super("maven");
  }

  @Override
  public String hierarchyMarkerType() {
    return "org.apache.maven.cli.MavenCli";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return extendsClass(named(hierarchyMarkerType()));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".MavenUtils",
      packageName + ".MavenExecutionListener",
      packageName + ".MavenProjectConfigurator",
      packageName + ".MavenLifecycleParticipant",
    };
  }

  @Override
  public boolean isApplicable(Set<TargetSystem> enabledSystems) {
    return super.isApplicable(enabledSystems)
        && Config.get().isCiVisibilityBuildInstrumentationEnabled();
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        named("customizeContainer")
            .and(takesArgument(0, named("org.codehaus.plexus.PlexusContainer"))),
        MavenInstrumentation.class.getName() + "$MavenAdvice");
  }

  public static class MavenAdvice {
    @Advice.OnMethodEnter
    public static void addLifecycleExtension(@Advice.Argument(0) final PlexusContainer container) {
      container.addComponent(
          new MavenLifecycleParticipant(), AbstractMavenLifecycleParticipant.class, null);
    }
  }
}
