import filters.Filter1;
import filters.Filter2;
import filters.Filter3;
import filters.Filter4;
import javax.inject.Inject;
import play.http.DefaultHttpFilters;

public class Filters extends DefaultHttpFilters {
  @Inject
  public Filters(Filter1 f1, Filter2 f2, Filter3 f3, Filter4 f4) {
    super(f1, f2, f3, f4);
  }
}
