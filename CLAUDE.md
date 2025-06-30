# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Small Town is a Spring Boot application for curating and managing tech blog content from various companies. The system crawls corporate tech blogs, stores articles, and provides a web interface for viewing curated content.

## Common Commands

### Build and Test
- `./gradlew build` - Build the entire project
- `./gradlew test` - Run all tests
- `./gradlew test --tests "*ClassName*"` - Run specific test class
- `./gradlew test --tests "*ClassName*methodName*"` - Run specific test method
- `./gradlew bootRun` - Run the Spring Boot application

### Database
- Uses MySQL in production (`mysql:3306/small_town`)
- Uses H2 in-memory database for tests
- JPA with Hibernate for ORM, DDL auto-update enabled

## Architecture

### Module Structure
- **article/**: Main article display and management (public-facing)
- **corporation/**: Company management and admin interface  
- **crawler/**: Web crawling system with Selenium-based blog crawlers

### Key Architecture Patterns

**Dual Entity Pattern**: The system maintains separate entity hierarchies for different concerns:
- `article.entity.*` - Public article display entities
- `crawler.entity.*` - Crawler-specific entities with different relationships

**Crawler Plugin System**: 
- `BlogCrawler` interface with implementations like `DefaultBlogCrawler`, `TistoryCrawler`
- Crawlers are selected based on `canHandle(blogUrl)` method
- Uses Spring ApplicationContext to discover crawler implementations

**Concurrent Crawling**:
- `CrawlingService` uses ExecutorService with configurable thread pool
- Crawls multiple corporations concurrently
- Transaction management per corporation to prevent partial failures

### Database Relationships
- Corporation -> Articles (one-to-many)
- Articles -> Tags (many-to-many via ArticleTag)
- Corporation -> Industries (many-to-many via CorporationIndustry)

### Configuration
- Main config: `application.properties` 
- Test config: `application-test.properties` (H2 database)
- Crawler settings: `CrawlerProperties` class with `crawler.*` properties
- WebDriver settings: `WebDriverProperties` class with `webdriver.*` properties

### Scheduling
- Quartz scheduler for automated crawling
- Configurable via `crawler.schedule.cron` property
- Default: Daily at 2 AM (`0 0 2 * * ?`)

### Testing Patterns
- Integration tests use `@SpringBootTest` with H2 database
- Crawler tests mock BlogCrawler implementations via `CrawlingTestConfig`
- Test fixtures in `CrawlingTestFixture` for consistent test data

## Important Notes

### BlogCrawler Interface
The `crawl` method signature is: `crawl(WebDriver driver, Corporation corporation)`
- First parameter: WebDriver instance
- Second parameter: Corporation entity

### Transaction Boundaries
- `CrawlingService` methods are transactional
- Each corporation crawl is wrapped in its own transaction
- Failed crawls don't affect other corporations

### Selenium Configuration
- Chrome WebDriver configured in `WebDriverConfig`
- Headless mode enabled in production
- WebDriverManager handles driver binary management