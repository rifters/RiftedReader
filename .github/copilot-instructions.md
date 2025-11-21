# GitHub Copilot Instructions for RiftedReader

## Project Overview

RiftedReader is a modern ebook reader for Android, inspired by LibreraReader. The project is currently in the **planning phase** with comprehensive documentation already created. The goal is to build a feature-rich ebook reader with a special focus on advanced Text-to-Speech (TTS) capabilities.

## Project Status

🚧 **In Planning Phase** - No implementation code exists yet. Comprehensive analysis and planning documents are complete and ready for implementation.

## Technology Stack

- **Language**: Kotlin (use exclusively)
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Architecture**: MVVM with Repository pattern (Clean Architecture)
- **Database**: Room
- **Dependency Injection**: Hilt
- **Async Operations**: Kotlin Coroutines + Flow
- **UI Framework**: Material Design 3 Components
- **Future Consideration**: Jetpack Compose (not immediate priority)

## Key Documentation Files

Before making any changes, **always consult these documentation files**:

1. **[LIBRERA_ANALYSIS.md](../docs/LIBRERA_ANALYSIS.md)** - Complete technical analysis of LibreraReader
   - Format support and parsing architecture
   - Database and library management
   - TTS feature analysis (most important)
   - Implementation recommendations

2. **[TTS_IMPLEMENTATION_GUIDE.md](../docs/TTS_IMPLEMENTATION_GUIDE.md)** - Detailed TTS implementation guide
   - Complete TTS system architecture
   - Advanced replacement/substitution system
   - Code examples and patterns
   - Background service setup

3. **[UI_UX_DESIGN_GUIDE.md](../UI_UX_DESIGN_GUIDE.md)** - UI/UX specifications
   - Color schemes and theming
   - Screen layouts and components
   - Gesture controls and navigation patterns
   - Accessibility requirements

4. **[IMPLEMENTATION_ROADMAP.md](../IMPLEMENTATION_ROADMAP.md)** - 20-week development plan
   - Staged implementation approach
   - Week-by-week breakdown
   - Milestones and deliverables

5. **[ARCHITECTURE.md](../ARCHITECTURE.md)** - Visual system architecture
   - Layer diagrams and component relationships

6. **[QUICK_START.md](../QUICK_START.md)** - Developer quick reference
   - Getting started guide
   - Key concepts overview

## Architecture Principles

### Package Structure
```
app/src/main/java/com/rifters/riftedreader/
├── data/           # Data layer (Room, repositories, file I/O)
├── domain/         # Business logic (models, parsers, use cases)
├── ui/             # Presentation layer (Activities, Fragments, ViewModels)
└── util/           # Utilities and extensions
```

### Layer Guidelines

- **Data Layer**: Handle all data operations (database, file system, preferences)
- **Domain Layer**: Business logic, format parsers, TTS engine, use cases
- **UI Layer**: ViewModels, Activities, Fragments - follow MVVM pattern
- **No direct UI-to-Data communication**: Always go through ViewModels and Repositories

## Coding Standards

### Kotlin Standards
- Use Kotlin idioms (data classes, extension functions, sealed classes)
- Prefer `val` over `var` whenever possible
- Use nullable types appropriately (`?` operator)
- Leverage Kotlin Coroutines for async operations
- Use `Flow` for reactive data streams

### Architecture Patterns
- Follow MVVM strictly: ViewModels should not reference Android framework classes (except AndroidViewModel when needed)
- Use Repository pattern for data access
- Use Hilt for dependency injection
- Keep Activities/Fragments as thin as possible (delegate to ViewModels)
- Use single-responsibility principle for classes

### Naming Conventions
- **Activities**: `*Activity.kt` (e.g., `ReaderActivity.kt`)
- **Fragments**: `*Fragment.kt` (e.g., `LibraryFragment.kt`)
- **ViewModels**: `*ViewModel.kt` (e.g., `ReaderViewModel.kt`)
- **Repositories**: `*Repository.kt` (e.g., `BookRepository.kt`)
- **DAOs**: `*Dao.kt` (e.g., `BookMetaDao.kt`)
- **Entities**: Descriptive names (e.g., `BookMeta.kt`, `ReadingSession.kt`)
- **Parsers**: `*Parser.kt` (e.g., `EpubParser.kt`, `PdfParser.kt`)

### Code Style
- Use 4 spaces for indentation
- Follow Android Kotlin style guide
- Add KDoc comments for public APIs
- Keep functions focused and under 50 lines when possible
- Prefer composition over inheritance

## Key Features & Priorities

### Core Priorities (In Order)
1. **TTS System** ⭐ - The standout feature of this app
   - Advanced replacement/substitution system
   - Background service with notification controls
   - @Voice Reader compatibility
   - Special commands support (SKIP, STOP, NEXT, PAUSE)

2. **Format Support** - Multi-format parsing
   - Priority: TXT, EPUB, PDF (start here)
   - Secondary: MOBI, AZW, FB2, CBZ, CBR
   - Use existing libraries where appropriate

3. **Library Management** - File scanning and metadata
   - Fast scanning algorithm
   - SQLite database with Room
   - Collections and tags system

4. **Reading Experience** - Customizable reader
   - Multiple themes (Light, Dark, Sepia, Black OLED)
   - Gesture-based navigation
   - Configurable tap zones
   - Bookmarks and highlights

## Build and Test Guidelines

### Build Commands (Once Implementation Starts)
```bash
# Build the project
./gradlew build

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest

# Run linter
./gradlew lint
```

### Testing Strategy
- Write unit tests for business logic (domain layer)
- Write instrumented tests for UI components
- Mock external dependencies (use MockK for Kotlin)
- Test TTS replacement engine thoroughly (critical feature)
- Test parser implementations with various file formats

### Dependencies Management
- Keep dependencies up-to-date but stable
- Prefer AndroidX libraries
- Use version catalog for dependency management
- Document reasons for adding new libraries

## TTS System - Special Attention Required

The TTS replacement system is the **most important and complex feature**. When working on TTS:

1. **Reference**: Always consult `docs/TTS_IMPLEMENTATION_GUIDE.md` first
2. **Replacement Types**: Support simple text, regex, and special commands
3. **Background Service**: Must work reliably in background with notification controls
4. **Performance**: Text processing must be efficient (large books)
5. **Compatibility**: Follow @Voice Reader patterns where applicable

## Common Tasks & Guidelines

### Adding a New Format Parser
1. Review parsing architecture in `LIBRERA_ANALYSIS.md` section 1
2. Create parser interface implementation in `domain/parser/`
3. Add corresponding tests
4. Register parser in `ParserManager`
5. Update supported formats documentation

### Adding UI Features
1. Consult `UI_UX_DESIGN_GUIDE.md` for design specs
2. Follow Material Design 3 guidelines
3. Implement in MVVM pattern
4. Support all defined themes
5. Ensure accessibility compliance

### Database Changes
1. Create/modify entities in `data/database/entity/`
2. Update DAOs in `data/database/dao/`
3. Increment database version in `BookDatabase`
4. Provide migration strategy
5. Update repositories accordingly

## File Organization

### Resource Files
- **Layouts**: Use descriptive names (`activity_reader.xml`, `fragment_library.xml`)
- **Drawables**: Use vector drawables when possible
- **Strings**: All user-facing text in `strings.xml` (prepare for i18n)
- **Colors**: Define in `colors.xml`, use Material Design color system
- **Themes**: Support Light, Dark, Sepia, Black themes

### Assets
- **Fonts**: Place in `assets/fonts/`
- **Sample Files**: Use `assets/samples/` for testing (if needed)

## Git Workflow

- Use feature branches: `feature/description`
- Keep commits focused and atomic
- Write descriptive commit messages
- Reference issues in commits when applicable
- Keep the main branch stable

## Documentation Updates

When making significant changes:
- Update relevant documentation files
- Keep README.md current with project status
- Update IMPLEMENTATION_ROADMAP.md progress
- Document new dependencies or architecture decisions

## Security & Privacy

- **No hardcoded secrets**: Use BuildConfig or environment variables
- **User privacy**: No data collection without explicit consent
- **File access**: Request minimal necessary permissions
- **Secure storage**: Use Android Keystore for sensitive data if needed

## Performance Considerations

- **Large files**: Handle books of 10MB+ efficiently
- **Database queries**: Use Room with proper indexing
- **UI rendering**: Smooth scrolling is critical (60fps target)
- **Background work**: Use WorkManager for scheduled tasks
- **Memory management**: Be mindful of bitmap and large text handling

## Accessibility

- Support TalkBack
- Provide content descriptions
- Support font scaling
- Ensure sufficient contrast ratios
- Support keyboard navigation

## Gotchas & Known Issues

- **PDF rendering**: Can be resource-intensive; consider pagination
- **EPUB styles**: May conflict with user preferences; need override system
- **TTS boundaries**: Sentence detection is language-dependent
- **File permissions**: Android 11+ scoped storage requires special handling

## When in Doubt

1. Check the relevant documentation file first
2. Follow established patterns in the codebase
3. Prioritize TTS functionality - it's the key differentiator
4. Keep code clean, testable, and maintainable
5. Ask for clarification rather than making assumptions

## License

License to be determined after initial implementation. Do not make assumptions about licensing in code comments or headers yet.

---

**Last Updated**: 2025-11-13
