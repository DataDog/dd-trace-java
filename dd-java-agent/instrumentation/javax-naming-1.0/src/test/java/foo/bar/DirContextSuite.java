package foo.bar;

import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;

public interface DirContextSuite {

  NamingEnumeration<SearchResult> search(
      final String name, final String filter, final SearchControls cons) throws NamingException;

  NamingEnumeration<SearchResult> search(
      final Name name, final String filter, final SearchControls cons) throws NamingException;

  NamingEnumeration<SearchResult> search(
      final String name, final String filter, final Object[] args, final SearchControls cons)
      throws NamingException;

  NamingEnumeration<SearchResult> search(
      final Name name, final String filter, final Object[] args, final SearchControls cons)
      throws NamingException;
}
