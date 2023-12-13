package datadog.smoketest.appsec.springbootjdbcpostgresql.model;

import java.sql.Date;

public class Dog {
  private long id;
  private String name;
  private String breed;
  private Date birthDate;
  private String profileImageUrl;
  private String publicDetails;
  private String creditCardNumber;
  private String socialIdNumber;

  public Dog() {}
  public Dog(String name, String breed, Date birthDate, String profileImageUrl, String publicDetails, String creditCardNumber, String socialIdNumber) {
    this.name = name;
    this.breed = breed;
    this.birthDate = birthDate;
    this.profileImageUrl = profileImageUrl;
    this.publicDetails = publicDetails;
    this.creditCardNumber = creditCardNumber;
    this.socialIdNumber = socialIdNumber;
  }

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getBreed() {
    return breed;
  }

  public void setBreed(String breed) {
    this.breed = breed;
  }

  public Date getBirthDate() {
    return birthDate;
  }

  public void setBirthDate(Date birthDate) {
    this.birthDate = birthDate;
  }

  public String getProfileImageUrl() {
    return profileImageUrl;
  }

  public void setProfileImageUrl(String profileImageUrl) {
    this.profileImageUrl = profileImageUrl;
  }

  public String getPublicDetails() {
    return publicDetails;
  }

  public void setPublicDetails(String publicDetails) {
    this.publicDetails = publicDetails;
  }

  public String getCreditCardNumber() {
    return creditCardNumber;
  }

  public void setCreditCardNumber(String creditCardNumber) {
    this.creditCardNumber = creditCardNumber;
  }

  public String getSocialIdNumber() {
    return socialIdNumber;
  }

  public void setSocialIdNumber(String socialIdNumber) {
    this.socialIdNumber = socialIdNumber;
  }

  @Override
  public String toString() {
    final StringBuffer sb = new StringBuffer("Dog{");
    sb.append("id=").append(id);
    sb.append(", dogName='").append(name).append('\'');
    sb.append(", breed='").append(breed).append('\'');
    sb.append(", birthDate=").append(birthDate);
    sb.append(", profileImageUrl='").append(profileImageUrl).append('\'');
    sb.append(", publicDetails='").append(publicDetails).append('\'');
    sb.append(", creditCardNumber='").append(creditCardNumber).append('\'');
    sb.append(", socialIdNumber='").append(socialIdNumber).append('\'');
    sb.append('}');
    return sb.toString();
  }
}
