package datadog.smoketest.appsec.springbootjdbcpostgresql.repository;

import datadog.smoketest.appsec.springbootjdbcpostgresql.model.Dog;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JdbcDogRepository implements DogRepository {

  private final JdbcTemplate jdbcTemplate;

  public JdbcDogRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public int save(Dog dog) {
    return jdbcTemplate.update(
        "INSERT INTO dogs (name, breed, birth_date, profile_image_url, public_details, credit_card_number, social_id_number) VALUES ('" +
            dog.getName() + "', '" +
            dog.getBreed() + "', '" +
            dog.getBirthDate() + "', '" +
            dog.getProfileImageUrl() + "', '" +
            dog.getPublicDetails() + "', '" +
            dog.getCreditCardNumber() + "', '" +
            dog.getSocialIdNumber() + "')");
  }

  @Override
  public int update(Dog dog) {
    return jdbcTemplate.update(
        "UPDATE dogs SET name='"+dog.getName()+
            "', breed='" + dog.getBreed() +
            "', birth_date='" + dog.getBirthDate() +
            "', profile_image_url='" + dog.getProfileImageUrl() +
            "', public_details='" + dog.getPublicDetails() +
            "', credit_card_number='" + dog.getCreditCardNumber() +
            "', social_id_number='" + dog.getSocialIdNumber() +
            "' WHERE id=" + dog.getId());
  }

  @Override
  public Dog findById(Long id) {
    try {
      Dog dog =
          jdbcTemplate.queryForObject(
              "SELECT * FROM dogs WHERE id=?",
              BeanPropertyRowMapper.newInstance(Dog.class),
              id);
      return dog;
    } catch (IncorrectResultSizeDataAccessException e) {
      return null;
    }
  }

  @Override
  public List<Dog> findAll() {
    return jdbcTemplate.query(
        "SELECT * FROM dogs", BeanPropertyRowMapper.newInstance(Dog.class));
  }

  @Override
  public int deleteById(Long id) {
    return jdbcTemplate.update("DELETE FROM dogs WHERE id=?", id);
  }
}
