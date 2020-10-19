import filters.*;
import javax.inject.Inject;
import play.api.mvc.*;
import play.http.HttpFilters;

public class Filters implements HttpFilters {
  private final Filter f1;
  private final Filter f2;
  private final Filter f3;
  private final Filter f4;

  @Inject
  public Filters(Filter1 f1, Filter2 f2, Filter3 f3, Filter4 f4) {
    this.f1 = f1;
    this.f2 = f2;
    this.f3 = f3;
    this.f4 = f4;
  }

  @Override
  public EssentialFilter[] filters() {
    return new EssentialFilter[] {f1, f2, f3, f4};
  }
}
