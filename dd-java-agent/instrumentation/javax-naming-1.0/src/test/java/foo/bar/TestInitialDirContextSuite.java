package foo.bar;

import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestInitialDirContextSuite implements DirContextSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestInitialDirContextSuite.class);

  private final InitialDirContext ctx;

  public TestInitialDirContextSuite(final InitialDirContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public NamingEnumeration<SearchResult> search(
      final String name, final String filter, final SearchControls cons) throws NamingException {
    LOGGER.debug("Before InitialDirContext search {} {} {}", name, filter, cons);
    final NamingEnumeration<SearchResult> result = ctx.search(name, filter, cons);
    LOGGER.debug("After InitialDirContext search {}", result);
    return result;
  }

  @Override
  public NamingEnumeration<SearchResult> search(
      final Name name, final String filter, final SearchControls cons) throws NamingException {
    LOGGER.debug("Before InitialDirContext search {} {} {}", name, filter, cons);
    final NamingEnumeration<SearchResult> result = ctx.search(name, filter, cons);
    LOGGER.debug("After InitialDirContext search {}", result);
    return result;
  }

  @Override
  public NamingEnumeration<SearchResult> search(
      final String name, final String filter, final Object[] args, final SearchControls cons)
      throws NamingException {
    LOGGER.debug("Before InitialDirContext search {} {} {} {}", name, filter, args, cons);
    final NamingEnumeration<SearchResult> result = ctx.search(name, filter, args, cons);
    LOGGER.debug("After InitialDirContext search {}", result);
    return result;
  }

  @Override
  public NamingEnumeration<SearchResult> search(
      final Name name, final String filter, final Object[] args, final SearchControls cons)
      throws NamingException {
    LOGGER.debug("Before InitialDirContext search {} {} {} {}", name, filter, args, cons);
    final NamingEnumeration<SearchResult> result = ctx.search(name, filter, args, cons);
    LOGGER.debug("After InitialDirContext search {}", result);
    return result;
  }
}
