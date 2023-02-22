package datadog.trace.plugin.csi;

import com.github.javaparser.symbolsolver.model.resolution.TypeSolver;
import datadog.trace.plugin.csi.HasErrors.HasErrorsException;
import datadog.trace.plugin.csi.util.MethodType;
import java.lang.reflect.Executable;
import java.util.Collection;
import javax.annotation.Nonnull;
import org.objectweb.asm.Type;

public interface TypeResolver extends TypeSolver {

  @Nonnull
  Class<?> resolveType(@Nonnull Type type) throws ResolutionException;

  @Nonnull
  Executable resolveMethod(@Nonnull MethodType method) throws ResolutionException;

  class ResolutionException extends HasErrorsException {

    public ResolutionException(@Nonnull final HasErrors errors) {
      super(errors);
    }

    public ResolutionException(@Nonnull final Collection<Failure> errors) {
      super(errors);
    }

    public ResolutionException(@Nonnull final Failure... errors) {
      super(errors);
    }
  }
}
