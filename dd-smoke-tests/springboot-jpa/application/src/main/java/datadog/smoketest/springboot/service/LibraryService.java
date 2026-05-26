package datadog.smoketest.springboot.service;

import datadog.smoketest.springboot.entity.Author;
import datadog.smoketest.springboot.entity.Book;
import datadog.smoketest.springboot.entity.Library;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class LibraryService {

  @PersistenceContext private EntityManager em;

  @Transactional
  public void save(final Library library) {
    em.persist(library);
  }

  @Transactional
  public Library update(Library library) {
    library.increaseUpdateCount();
    library.getBooks().forEach(Book::increaseUpdateCount);
    library.getBooks().stream()
        .map(Book::getId)
        .map(this::findBookById)
        .forEach(
            book -> {
              book.getAuthors().forEach(Author::increaseUpdateCount);
              book.getOwner().increaseUpdateCount();
            });
    return em.merge(library);
  }

  public Library findLibraryById(int id) {
    return em.find(Library.class, id);
  }

  public Book findBookById(int id) {
    return em.find(Book.class, id);
  }
}
