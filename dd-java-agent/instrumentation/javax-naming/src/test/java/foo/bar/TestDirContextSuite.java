package foo.bar;

import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestDirContextSuite implements DirContextSuite {

  private static final Logger LOGGER = LoggerFactory.getLogger(TestDirContextSuite.class);

  private final DirContext ctx;

  public TestDirContextSuite(final DirContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public NamingEnumeration<SearchResult> search(
      final String name, final String filter, final SearchControls cons) throws NamingException {
    LOGGER.debug("Before DirContext search {} {} {}", name, filter, cons);
    final NamingEnumeration<SearchResult> result = ctx.search(name, filter, cons);
    LOGGER.debug("After DirContext search {}", result);
    return result;
  }

  @Override
  public NamingEnumeration<SearchResult> search(
      final Name name, final String filter, final SearchControls cons) throws NamingException {
    LOGGER.debug("Before DirContext search {} {} {}", name, filter, cons);
    final NamingEnumeration<SearchResult> result = ctx.search(name, filter, cons);
    LOGGER.debug("After DirContext search {}", result);
    return result;
  }

  @Override
  public NamingEnumeration<SearchResult> search(
      final String name, final String filter, final Object[] args, final SearchControls cons)
      throws NamingException {
    LOGGER.debug("Before DirContext search {} {} {} {}", name, filter, args, cons);
    final NamingEnumeration<SearchResult> result = ctx.search(name, filter, args, cons);
    LOGGER.debug("After DirContext search {}", result);
    return result;
  }

  @Override
  public NamingEnumeration<SearchResult> search(
      final Name name, final String filter, final Object[] args, final SearchControls cons)
      throws NamingException {
    LOGGER.debug("Before DirContext search {} {} {} {}", name, filter, args, cons);
    final NamingEnumeration<SearchResult> result = ctx.search(name, filter, args, cons);
    LOGGER.debug("After DirContext search {}", result);
    return result;
  }
}
