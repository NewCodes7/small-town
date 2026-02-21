# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/com/newcodes7/small_town/` holds the Spring Boot application code, organized by domain modules such as `article/`, `video/`, `theme/`, `crawler/`, `embedding/`, `auth/`, and `global/`.
- `src/main/resources/` contains configuration (`application-*.properties`), DB migrations (`db/migration/*.sql`), server templates (`templates/`), and static assets (`static/css`, `static/js`).
- `src/test/java/` contains integration-style tests; `src/test/resources/` includes `application-test.properties` and test fixtures.

## Build, Test, and Development Commands
- `./gradlew build` builds the project and runs checks.
- `./gradlew bootRun` runs the application locally.
- `./gradlew test` runs all tests (JUnit 5).
- `./gradlew test --tests "*ClassName*"` runs a specific test class.

## Coding Style & Naming Conventions
- Java 17 toolchain; follow standard Spring Boot conventions.
- Use 4-space indentation, keep classes in the correct module package, and avoid exposing entities directly (use DTOs).
- Naming patterns (from existing code):
  - Entity: `Article`, `Corporation`
  - Repository: `EntityNameRepository`
  - Service: `EntityNameService`
  - DTO: `EntityNameRequestDto` / `EntityNameResponseDto`
  - Boolean fields: `is/has/can` prefix

## Testing Guidelines
- Tests are primarily integration tests using PostgreSQL (see `TESTING_GUIDE.md`).
- Test classes live in `src/test/java` and use `@SpringBootTest` with `application-test.properties`.
- Naming: `*ServiceTest`, `*RepositoryTest`, and method names like `signup_Success`.
- Run targeted tests with `./gradlew test --tests "*AuthServiceTest.signup_Success"`.

## Commit & Pull Request Guidelines
- Commits follow a conventional prefix style: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`.
- Pull requests should include:
  - A concise summary of changes and rationale.
  - Test evidence (command output or notes). If not tested, explain why.
  - Links to related issues and screenshots for UI/admin changes.

## Security & Configuration Tips
- Keep secrets out of the repo; use `application-*.properties` or environment variables.
- For local tests, configure `src/test/resources/application-test.properties` with PostgreSQL and optional API keys (empty values are acceptable for mocks).
