# validate-java-project/validate-java-project/README.md

# Validate Java Project

This project is a Java application that validates the structure and inheritance of Java classes within a specified project directory. It ensures that class names follow specific naming conventions and that the inheritance hierarchy adheres to defined rules.

## Project Structure

The project is organized as follows:

```
validate-java-project
├── src
│   ├── main
│   │   ├── java
│   │   │   ├── com
│   │   │   │   └── example
│   │   │   │       ├── Main.java          # Entry point of the application
│   │   │   │       ├── Validator.java     # Contains validation logic
│   │   │   │       ├── JavaFileParser.java # Parses Java files using JavaParser
│   │   │   │       └── KebabCaseValidator.java # Validates kebab-case format
│   │   └── resources
├── pom.xml                                  # Maven configuration file
└── README.md                                 # Project documentation
```

## Getting Started

To set up and run the validation tool, follow these steps:

1. **Clone the repository**:
   ```
   git clone <repository-url>
   cd validate-java-project
   ```

2. **Build the project**:
   Use Maven to build the project and download the necessary dependencies:
   ```
   mvn clean install
   ```

3. **Run the application**:
   Execute the `Main` class to start the validation process:
   ```
   mvn exec:java -Dexec.mainClass="com.datadog.convention.Checker"
   ```

## Usage

The application will prompt you to specify the base directory of the Java project you wish to validate. It will then check for:

- Kebab-case naming for subdirectories.
- Inheritance rules for Java classes, ensuring that class names and their relationships conform to the specified conventions.

## Dependencies

This project uses the following dependencies:

- **JavaParser**: A library for parsing Java code and generating an abstract syntax tree (AST).

## Contributing

Contributions are welcome! Please feel free to submit a pull request or open an issue for any enhancements or bug fixes.

## License

This project is licensed under the MIT License. See the LICENSE file for more details.
