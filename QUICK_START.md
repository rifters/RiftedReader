# RiftedReader Quick Start Guide

**Updated for Stage 1-3 Implementation**

This guide will help you get started with the RiftedReader Android application that has been built through Stages 1-3 of the implementation roadmap.

## What's Been Built

✅ **Stages 1-3 Complete** (Weeks 1-7 of roadmap)
- Android project with modern architecture (MVVM + Repository)
- Room database for book metadata
- File scanner for discovering books
- Parsers for TXT, EPUB, and PDF formats
- Library UI with search and display
- Reader UI with page navigation
- Storage permission handling

## Prerequisites

To build and run RiftedReader, you need:

1. **Android Studio**: Arctic Fox (2020.3.1) or newer
   - Download from: https://developer.android.com/studio

2. **Android SDK**: 
   - Minimum SDK: 24 (Android 7.0)
   - Target SDK: 34 (Android 14)
   - Build Tools: 34.0.0

3. **JDK**: Java Development Kit 17
   - Bundled with Android Studio or download from: https://adoptium.net/

4. **Gradle**: 8.2 (included via wrapper)

## Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/rifters/RiftedReader.git
cd RiftedReader
```

### 2. Open in Android Studio

1. Launch Android Studio
2. Select "Open an Existing Project"
3. Navigate to the RiftedReader directory
4. Click "OK"

Android Studio will automatically:
- Download Gradle wrapper
- Sync Gradle files
- Download dependencies
- Index the project

### 3. Configure the Project

The project should work out of the box. If you encounter issues:

1. **Sync Gradle**: File → Sync Project with Gradle Files
2. **Update SDK**: Tools → SDK Manager (ensure SDK 34 is installed)
3. **Clean Project**: Build → Clean Project
4. **Rebuild**: Build → Rebuild Project

### 4. Run the Application

#### On an Emulator:

1. **Create AVD** (if needed):
   - Tools → Device Manager
   - Click "Create Device"
   - Select a phone (e.g., Pixel 4)
   - Choose system image (Android 11+ recommended)
   - Click "Finish"

2. **Run**:
   - Click the "Run" button (green triangle)
   - Select your emulator
   - Wait for app to launch

#### On a Physical Device:

1. **Enable Developer Options** on your device:
   - Go to Settings → About Phone
   - Tap "Build Number" 7 times
   - Go back to Settings → Developer Options
   - Enable "USB Debugging"

2. **Connect** device via USB

3. **Run**:
   - Click the "Run" button
   - Select your device
   - Approve USB debugging on device
   - App will install and launch

## Using the Application

### First Launch

1. The app opens to an empty library screen
2. Tap the FAB (Floating Action Button) with scan icon
3. Grant storage permission when prompted
4. The app will scan for books in:
   - `/storage/emulated/0/Books/`
   - `/storage/emulated/0/Download/`
   - `/storage/emulated/0/Documents/`

### Adding Test Books

To test the app, add some ebook files:

1. **Using Android Studio Device File Explorer**:
   - View → Tool Windows → Device File Explorer
   - Navigate to `/sdcard/Books/` (create if needed)
   - Right-click → Upload
   - Select your test files (.txt, .epub, .pdf)

2. **Using ADB**:
   ```bash
   adb push /path/to/book.epub /sdcard/Books/
   ```

### Reading a Book

1. After scanning, books appear in the library
2. Tap a book to open the reader
3. **Tap the screen** to show/hide controls
4. Use **Previous/Next** buttons to navigate
5. Use the **slider** to jump to specific pages
6. Reading progress is automatically saved

### Search

1. Type in the search box at the top of the library
2. Search looks for matches in title and author
3. Clear search to see all books again

## Project Structure

```
RiftedReader/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/rifters/riftedreader/
│   │   │   │   ├── data/              # Database & repositories
│   │   │   │   ├── domain/            # Business logic & parsers
│   │   │   │   ├── ui/                # Activities & fragments
│   │   │   │   └── util/              # Utilities
│   │   │   ├── res/                   # Resources (layouts, strings, etc.)
│   │   │   └── AndroidManifest.xml
│   │   └── test/                      # Unit tests
│   └── build.gradle.kts               # App-level build config
├── gradle/                            # Gradle wrapper
├── build.gradle.kts                   # Project-level build config
├── settings.gradle.kts                # Gradle settings
└── README.md                          # Project documentation
```

## Key Files to Know

### Database
- `BookMeta.kt`: Book entity with all metadata
- `BookMetaDao.kt`: Database queries
- `BookDatabase.kt`: Room database setup

### Parsers
- `TxtParser.kt`: Plain text files
- `EpubParser.kt`: EPUB files
- `PdfParser.kt`: PDF files (minimal, uses viewer library)

### UI
- `LibraryFragment.kt`: Main library screen
- `ReaderActivity.kt`: Reading screen
- `BooksAdapter.kt`: RecyclerView for book list

## Development Tasks

### Running Tests

```bash
# Unit tests
./gradlew test

# See test results
open app/build/reports/tests/testDebugUnitTest/index.html
```

### Building APK

```bash
# Debug build
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk

# Release build (requires signing setup)
./gradlew assembleRelease
```

### Code Style

The project follows Kotlin coding conventions:
- 4-space indentation
- Descriptive variable names
- Comments for complex logic
- KDoc for public APIs

Use Android Studio's code formatter:
- Code → Reformat Code (Ctrl+Alt+L / Cmd+Option+L)

## Troubleshooting

### Gradle Sync Issues

```bash
# Clean and rebuild
./gradlew clean
./gradlew build
```

### Permission Issues on Device

If the app can't access files:
1. Settings → Apps → RiftedReader
2. Permissions → Files and media → Allow

For Android 11+, you may need "All files access":
1. Settings → Apps → RiftedReader
2. Permissions → Special app access
3. All files access → Enable

### Build Errors

Common fixes:
1. Invalidate Caches: File → Invalidate Caches / Restart
2. Update Gradle: Help → Check for Updates
3. Update SDK: Tools → SDK Manager
4. Check `local.properties` has correct SDK path

### App Crashes

Check Logcat in Android Studio:
1. View → Tool Windows → Logcat
2. Filter by package: `com.rifters.riftedreader`
3. Look for red error messages

## Next Steps

After familiarizing yourself with the current implementation:

1. **Review the code**: Start with `MainActivity.kt` and follow the flow
2. **Read documentation**: Check `LIBRERA_ANALYSIS.md` for feature inspiration
3. **Explore TODO items**: Search for `TODO` comments in the code
4. **Check the roadmap**: `IMPLEMENTATION_ROADMAP.md` for next stages

### Stage 4: TTS Implementation (Next Priority)

The next major feature to implement is Text-to-Speech:
- See `TTS_IMPLEMENTATION_GUIDE.md` for detailed guide
- Key components: TTSEngine, TTSService, replacement system
- Expected timeline: 4 weeks (Weeks 11-14 of roadmap)

## Resources

- **Android Documentation**: https://developer.android.com/docs
- **Kotlin Documentation**: https://kotlinlang.org/docs/home.html
- **Material Design 3**: https://m3.material.io/
- **Room Database**: https://developer.android.com/training/data-storage/room

## Getting Help

- Check existing issues: https://github.com/rifters/RiftedReader/issues
- Create new issue: Describe your problem with logs/screenshots
- Review documentation files in the repository

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Commit your changes: `git commit -am 'Add some feature'`
4. Push to the branch: `git push origin feature/your-feature`
5. Create a Pull Request

---

**Happy Coding! 📚**

---

**Last Updated**: November 13, 2025  
**Project Status**: Stages 1-3 Complete
