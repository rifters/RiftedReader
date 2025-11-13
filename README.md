# RiftedReader
A modern ebook reader for Android, inspired by LibreraReader

## Project Status

🚧 **In Planning Phase** - Comprehensive analysis complete, ready to begin implementation

## Documentation

This project includes detailed planning and analysis documents:

### 📚 Core Documentation

1. **[LIBRERA_ANALYSIS.md](LIBRERA_ANALYSIS.md)** - Complete analysis of LibreraReader
   - Supported formats and parsing architecture
   - Database and library management
   - UI/UX patterns and design
   - File storage handling
   - Detailed TTS feature analysis
   - Dependencies and libraries
   - Implementation recommendations

2. **[TTS_IMPLEMENTATION_GUIDE.md](TTS_IMPLEMENTATION_GUIDE.md)** - TTS Implementation Guide
   - Complete TTS system architecture
   - Advanced replacement/substitution system
   - Code examples and implementation details
   - Background service setup
   - Testing strategies
   - Integration checklist

3. **[UI_UX_DESIGN_GUIDE.md](UI_UX_DESIGN_GUIDE.md)** - UI/UX Design Specifications
   - Color schemes and theming
   - Screen layouts and components
   - Gesture controls
   - Responsive design patterns
   - Accessibility features
   - Animation specifications

4. **[IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md)** - Development Roadmap
   - 20-week staged implementation plan
   - Week-by-week breakdown
   - Deliverables and success criteria
   - Risk management
   - Best practices

## Key Features (Planned)

### Format Support
- PDF, EPUB, EPUB3, MOBI, AZW, AZW3
- FB2, TXT, RTF, HTML
- CBZ, CBR (Comic books)
- DOC, DOCX

### Text-to-Speech ⭐ Core Feature
- Advanced TTS with Android TextToSpeech API
- **Sophisticated replacement system**:
  - Simple text replacements
  - Regular expression support
  - Special commands (SKIP, STOP, NEXT, PAUSE)
  - @Voice Reader compatibility
- Background reading with notification controls
- Customizable speed and pitch
- Sentence highlighting
- Auto-scroll during reading

### Library Management
- Fast file scanning
- Metadata extraction
- Collections and tags
- Advanced search and filtering
- Reading progress tracking
- Favorites system

### Reading Experience
- Highly customizable (fonts, colors, spacing)
- Multiple themes (Light, Dark, Sepia, Black OLED)
- Gesture-based navigation
- Configurable tap zones
- Page and scroll modes
- Bookmarks and highlights

### Modern Architecture
- Clean Architecture (MVVM)
- Kotlin Coroutines and Flow
- Room Database
- Material Design 3
- Jetpack Compose (future consideration)

## Technology Stack

- **Language**: Kotlin
- **Minimum SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Architecture**: MVVM with Repository pattern
- **Database**: Room
- **DI**: Hilt
- **Async**: Coroutines + Flow
- **UI**: Material Design 3 Components

## Development Stages

### Stage 1: Foundation (Weeks 1-2) - Current
- [x] Project analysis and planning
- [x] Documentation creation
- [ ] Project setup
- [ ] Basic UI framework

### Stage 2: Core Features (Weeks 3-10)
- [ ] File management and database
- [ ] Basic parsing (TXT, EPUB, PDF)
- [ ] Reader UI and customization

### Stage 3: TTS Implementation (Weeks 11-14) ⭐
- [ ] Basic TTS engine
- [ ] Replacement system
- [ ] Background service
- [ ] TTS controls UI

### Stage 4: Enhanced Features (Weeks 15-20)
- [ ] Additional format support
- [ ] Library enhancements
- [ ] Cloud sync (optional)

See [IMPLEMENTATION_ROADMAP.md](IMPLEMENTATION_ROADMAP.md) for complete timeline.

## Inspiration

This project is inspired by [LibreraReader](https://github.com/foobnix/LibreraReader) (GPL v3), a mature and feature-rich ebook reader. RiftedReader aims to bring similar functionality with a modern codebase and architecture, with special focus on the excellent TTS replacement system that makes LibreraReader stand out.

**Note**: RiftedReader is being built from scratch based on feature analysis, not by copying code from LibreraReader.

## License

TBD - Will be determined after initial implementation

## Contributing

Project is in planning phase. Contributions will be welcome once development begins.

## Contact

For questions or suggestions, please open an issue.

---

**Last Updated**: November 13, 2025
