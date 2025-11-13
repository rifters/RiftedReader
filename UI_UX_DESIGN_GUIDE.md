# UI/UX Design Guide - Mimicking LibreraReader

**Document Purpose**: Detailed UI/UX specifications for RiftedReader based on LibreraReader's design patterns

---

## Design Philosophy

LibreraReader follows these core principles:
1. **Function over form** - Features are prioritized over aesthetics
2. **Customization** - Users can tailor almost everything
3. **Efficiency** - Quick access to common actions
4. **Information density** - Show maximum useful information
5. **Accessibility** - Multiple ways to accomplish tasks

---

## Color Scheme and Theming

### Theme Options

LibreraReader provides multiple themes:

1. **Light Theme**
   - Background: #FFFFFF (white)
   - Text: #000000 (black)
   - Accent: #1976D2 (blue)
   - Card background: #F5F5F5 (light gray)

2. **Sepia Theme**
   - Background: #F4ECD8 (sepia)
   - Text: #5D4E37 (dark brown)
   - Accent: #8B4513 (saddle brown)

3. **Dark Theme**
   - Background: #212121 (dark gray)
   - Text: #FFFFFF (white)
   - Accent: #4CAF50 (green)

4. **Black (OLED) Theme**
   - Background: #000000 (pure black)
   - Text: #E0E0E0 (light gray)
   - Accent: #4CAF50 (green)

5. **Custom Themes**
   - Users can set any color for background, text, and accents

### Implementation

```kotlin
// Theme data class
data class ReaderTheme(
    val backgroundColor: Int,
    val textColor: Int,
    val accentColor: Int,
    val name: String,
    val isCustom: Boolean = false
)

// Predefined themes
object Themes {
    val LIGHT = ReaderTheme(
        backgroundColor = Color.WHITE,
        textColor = Color.BLACK,
        accentColor = Color.parseColor("#1976D2"),
        name = "Light"
    )
    
    val SEPIA = ReaderTheme(
        backgroundColor = Color.parseColor("#F4ECD8"),
        textColor = Color.parseColor("#5D4E37"),
        accentColor = Color.parseColor("#8B4513"),
        name = "Sepia"
    )
    
    val DARK = ReaderTheme(
        backgroundColor = Color.parseColor("#212121"),
        textColor = Color.WHITE,
        accentColor = Color.parseColor("#4CAF50"),
        name = "Dark"
    )
    
    val BLACK = ReaderTheme(
        backgroundColor = Color.BLACK,
        textColor = Color.parseColor("#E0E0E0"),
        accentColor = Color.parseColor("#4CAF50"),
        name = "Black"
    )
}
```

---

## Screen Layouts

### 1. Library/Home Screen

**Layout Structure:**

```
┌─────────────────────────────────────────┐
│  ☰  Library                   🔍  ⚙️   │ ← Toolbar (56dp)
├─────────────────────────────────────────┤
│  [Recent] [Authors] [Series] [Folders]  │ ← Tabs (48dp)
├─────────────────────────────────────────┤
│  Filters: ▼All Types  ▼Sort by: Recent │ ← Filter bar (40dp)
├─────────────────────────────────────────┤
│                                          │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐ │
│  │  Cover  │  │  Cover  │  │  Cover  │ │
│  │  Image  │  │  Image  │  │  Image  │ │
│  │  3:4    │  │  3:4    │  │  3:4    │ │
│  └─────────┘  └─────────┘  └─────────┘ │
│  Book Title   Book Title   Book Title  │ ← Grid View
│  Author Name  Author Name  Author Name  │   (3 columns)
│  ⭐️4.5 📖45%  ⭐️4.0 📖12%  ⭐️5.0 📖89% │
│                                          │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐ │
│  │  Cover  │  │  Cover  │  │  Cover  │ │
│  │  Image  │  │  Image  │  │  Image  │ │
│  │  3:4    │  │  3:4    │  │  3:4    │ │
│  └─────────┘  └─────────┘  └─────────┘ │
│  Book Title   Book Title   Book Title  │
│  Author Name  Author Name  Author Name  │
│  ⭐️4.2 📖0%   ⭐️3.8 📖100% ⭐️4.7 📖23% │
│                                          │
├─────────────────────────────────────────┤
│  📚 Library  🔊 TTS  ⭐️ Favorites  ⚙️   │ ← Bottom Nav (56dp)
└─────────────────────────────────────────┘
```

**Key Elements:**

1. **Toolbar (56dp height)**
   - Hamburger menu (navigation drawer)
   - Title "Library" or current view name
   - Search icon
   - Settings icon
   - More menu (3 dots)

2. **Tab Bar (48dp height)**
   - Recent books
   - All books
   - Authors
   - Series
   - Folders
   - Collections/Tags
   - Scrollable horizontally if needed

3. **Filter Bar (40dp height)**
   - Format filter dropdown (All, PDF, EPUB, MOBI, etc.)
   - Sort dropdown (Recent, Title A-Z, Author, Size, etc.)
   - View mode toggle (Grid/List)

4. **Book Grid/List**
   - Grid: 2-4 columns depending on screen size
   - List: Full-width items with cover thumbnail
   - Each item shows:
     * Cover image (3:4 aspect ratio)
     * Book title (max 2 lines)
     * Author name (max 1 line)
     * Reading progress (percentage + visual bar)
     * Rating (if available)
     * Format badge (small icon)
     * Quick action menu (long press or dots)

5. **Bottom Navigation (56dp height)**
   - Library
   - Recent
   - Favorites
   - Settings

**Grid Item Dimensions:**
- 3 columns on phone (portrait)
- 4-6 columns on tablet
- 2 columns on phone (landscape)

```xml
<!-- Grid item layout example -->
<androidx.cardview.widget.CardView
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_margin="4dp"
    app:cardElevation="2dp">
    
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="8dp">
        
        <ImageView
            android:id="@+id/coverImage"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:scaleType="centerCrop"
            android:adjustViewBounds="true"/>
        
        <TextView
            android:id="@+id/titleText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:maxLines="2"
            android:ellipsize="end"
            android:textSize="14sp"
            android:textStyle="bold"/>
        
        <TextView
            android:id="@+id/authorText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:maxLines="1"
            android:ellipsize="end"
            android:textSize="12sp"/>
        
        <ProgressBar
            android:id="@+id/progressBar"
            style="?android:attr/progressBarStyleHorizontal"
            android:layout_width="match_parent"
            android:layout_height="4dp"/>
        
    </LinearLayout>
</androidx.cardview.widget.CardView>
```

### 2. Book Reader Screen

**Layout Structure:**

```
┌─────────────────────────────────────────┐
│                                          │ ← Top tap zone (1/3)
│                                          │   Shows toolbar on tap
│            Chapter 5                     │
│                                          │
│    Lorem ipsum dolor sit amet,           │
│    consectetur adipiscing elit,          │
│    sed do eiusmod tempor                 │
│    incididunt ut labore et dolore        │ ← Middle tap zone (1/3)
│    magna aliqua.                         │   Shows menu on tap
│                                          │
│    Ut enim ad minim veniam, quis         │
│    nostrud exercitation ullamco          │
│    laboris nisi ut aliquip ex ea         │
│    commodo consequat.                    │
│                                          │
│                                          │ ← Bottom tap zone (1/3)
│                                          │   Shows toolbar on tap
├─────────────────────────────────────────┤
│ ▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░  Page 45/200  │ ← Progress footer
│ ◀️ ▶️  🔊  ☀️  Aa  🔖  ⋮               │ ← Quick controls
└─────────────────────────────────────────┘
```

**Tap Zones:**

LibreraReader uses a 3x3 tap grid:

```
┌──────────┬──────────┬──────────┐
│   Back   │  Toolbar │ Forward  │ ← Top row
├──────────┼──────────┼──────────┤
│   Back   │   Menu   │ Forward  │ ← Middle row
├──────────┼──────────┼──────────┤
│   Back   │  Toolbar │ Forward  │ ← Bottom row
└──────────┴──────────┴──────────┘
```

Users can customize actions for each zone.

**Overlay Controls (Auto-hide):**

1. **Top Toolbar (appears on tap)**
   ```
   ┌─────────────────────────────────┐
   │ ← Book Title            ⋮  ✕   │
   └─────────────────────────────────┘
   ```
   - Back button
   - Book title
   - More options (table of contents, bookmarks, etc.)
   - Close button

2. **Bottom Toolbar (appears on tap)**
   ```
   ┌─────────────────────────────────┐
   │ ▓▓▓▓▓▓▓░░░░░░░░░  Page 45/200  │
   │ ◀️ ▶️  🔊  ☀️  Aa  🔖  ⋮       │
   └─────────────────────────────────┘
   ```
   - Progress bar (seekable)
   - Page indicator
   - Previous/Next page buttons
   - TTS toggle
   - Brightness control
   - Font settings
   - Bookmarks
   - More options

**Reading Modes:**

1. **Scroll Mode**
   - Vertical continuous scrolling
   - Auto-scroll option
   - Smooth scrolling

2. **Page Mode**
   - Horizontal page flipping
   - Page curl animation (optional)
   - Two-page spread on tablets

3. **Reflow Mode** (for PDFs)
   - Extracts and reflows text
   - Adjustable text size
   - Better for small screens

**Text Customization Panel:**

```
┌─────────────────────────────────────┐
│         Text Settings               │
├─────────────────────────────────────┤
│  Font:  [Dropdown: Sans Serif ▼]   │
│                                     │
│  Size:  ─────●─────  18sp          │
│                                     │
│  Line Spacing:  ───●────  1.5x     │
│                                     │
│  Margins:  ────●─────  16dp        │
│                                     │
│  Alignment: [Left] [Center] [Both] │
│                                     │
│  Hyphenation: [x] Enable           │
│                                     │
│  Night Mode: [ ] Enable            │
│                                     │
│  [Apply]  [Reset]                  │
└─────────────────────────────────────┘
```

### 3. Navigation Drawer

**Structure:**

```
┌────────────────────────────────┐
│  👤 User Profile               │
│  user@example.com              │
├────────────────────────────────┤
│  📚 My Library                 │
│  📖 Current Book               │
│  ⏱️ Recent Books               │
│  ⭐️ Favorites                  │
│  📑 Collections                │
│  ├─ Work Books                 │
│  ├─ Fiction                    │
│  └─ Reference                  │
├────────────────────────────────┤
│  🔍 Search Library             │
│  📁 File Browser               │
│  🌐 OPDS Catalogs              │
│  ☁️ Cloud Storage              │
├────────────────────────────────┤
│  📊 Statistics                 │
│  ⚙️ Settings                   │
│  ❓ Help & FAQ                 │
│  ℹ️ About                      │
└────────────────────────────────┘
```

### 4. TTS Controls View

**Layout:**

```
┌─────────────────────────────────────┐
│     🔊 Text-to-Speech               │
├─────────────────────────────────────┤
│  Chapter 5: The Great Journey       │
│                                     │
│  "Lorem ipsum dolor sit amet,       │
│   consectetur adipiscing elit..."   │
│                                     │
│  ◀◀   ▶/⏸   ▶▶             ⏹     │
│   5s   Play   5s            Stop    │
│                                     │
│  ──────●──────────  01:23 / 05:47  │
│                                     │
│  Speed:  ────●─────  1.2x          │
│  Pitch:  ─────●────  1.0x          │
│                                     │
│  ⏱️ Timer: [None ▼]                │
│  📝 Replacements: [12 active]      │
│  🗣️ Voice: [Google EN-US ▼]       │
│                                     │
│  Options:                           │
│  [x] Auto-scroll                    │
│  [x] Highlight sentence             │
│  [ ] Stop at chapter end            │
│                                     │
│  [Close]    [Settings]              │
└─────────────────────────────────────┘
```

**TTS Notification:**

```
┌─────────────────────────────────────┐
│  📱 RiftedReader                    │
│  🔊 Reading: "The Great Book"       │
│  Chapter 5 - Page 127               │
│                                     │
│  ◀◀  ⏸  ▶▶  ⏹                     │
└─────────────────────────────────────┘
```

### 5. Settings Screen

**Organized in Categories:**

```
┌─────────────────────────────────────┐
│  ⚙️ Settings                        │
├─────────────────────────────────────┤
│  📖 Reading                         │
│    • Page turning animation         │
│    • Tap zones configuration        │
│    • Scroll behavior                │
│    • Status bar visibility          │
│                                     │
│  🎨 Appearance                      │
│    • Theme selection                │
│    • Font settings                  │
│    • Text size and spacing          │
│    • Color customization            │
│                                     │
│  🔊 Text-to-Speech                  │
│    • Default voice                  │
│    • Speed and pitch                │
│    • Replacement rules              │
│    • Auto-scroll settings           │
│                                     │
│  📚 Library                         │
│    • Scan folders                   │
│    • Auto-scan on startup           │
│    • Cover download                 │
│    • Metadata extraction            │
│                                     │
│  ☁️ Sync & Backup                   │
│    • Cloud service selection        │
│    • Sync frequency                 │
│    • Backup/restore                 │
│                                     │
│  🔒 Privacy & Security              │
│    • App lock                       │
│    • Hide recent books              │
│    • Clear history                  │
│                                     │
│  ℹ️ About                           │
│    • Version information            │
│    • Licenses                       │
│    • Support                        │
└─────────────────────────────────────┘
```

---

## Gesture Controls

### Standard Gestures

1. **Tap**
   - Single tap: Show/hide controls
   - Different zones can be customized
   - Default: center = menu, sides = page turn

2. **Swipe**
   - Left/Right: Previous/Next page
   - Up/Down: Scroll (in scroll mode)
   - Two-finger swipe: Quick access to features

3. **Pinch/Zoom**
   - Pinch in/out: Adjust text size
   - In PDF mode: Zoom into page
   - Quick reset: Double-tap

4. **Long Press**
   - On text: Select text, copy, dictionary lookup
   - On word: Quick dictionary
   - On image: Save or share

5. **Double Tap**
   - Toggle zoom
   - Toggle reading mode
   - User configurable

### Advanced Gestures

1. **Volume Button Navigation**
   - Volume Up/Down: Page forward/back
   - Configurable in settings

2. **Edge Swipe**
   - From left edge: Open drawer
   - From bottom edge: Show quick controls

---

## Component Specifications

### Typography

```kotlin
// Text styles
object TextStyles {
    val Title = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 28.sp
    )
    
    val Body = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp
    )
    
    val Caption = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp
    )
}
```

### Dimensions

```kotlin
object Dimensions {
    val ToolbarHeight = 56.dp
    val TabBarHeight = 48.dp
    val BottomNavHeight = 56.dp
    val CardElevation = 2.dp
    val CardCornerRadius = 4.dp
    val DefaultPadding = 16.dp
    val SmallPadding = 8.dp
    val TinyPadding = 4.dp
}
```

### Spacing System

- **Tiny**: 4dp - Between close elements
- **Small**: 8dp - Within cards
- **Medium**: 16dp - Between sections
- **Large**: 24dp - Major separations
- **XLarge**: 32dp - Screen margins

---

## Animations

### Page Turning Animations

1. **None** - Instant page change
2. **Fade** - Cross-fade between pages
3. **Slide** - Horizontal slide
4. **Curl** - Page curl effect (skeuomorphic)
5. **Flip** - 3D flip animation

```kotlin
// Animation duration
const val ANIMATION_DURATION_SHORT = 150L
const val ANIMATION_DURATION_MEDIUM = 300L
const val ANIMATION_DURATION_LONG = 500L
```

### Transition Animations

- Screen transitions: 300ms slide animation
- Dialog appearance: 200ms scale + fade
- Menu expansion: 250ms height animation
- Progress updates: 100ms smooth interpolation

---

## Accessibility Features

### Must-Have Accessibility

1. **Screen Reader Support**
   - Content descriptions on all interactive elements
   - Logical navigation order
   - Announcements for state changes

2. **High Contrast Mode**
   - Optional high contrast themes
   - Bold text option
   - Increased touch target sizes

3. **Font Scaling**
   - Respect system font size
   - Allow independent app font size
   - Minimum touch target: 48dp x 48dp

4. **Color Blind Friendly**
   - Don't rely solely on color
   - Use patterns/icons in addition to color
   - Test with color blind simulators

---

## Responsive Design

### Phone (Portrait)

- Grid: 3 columns
- Toolbar: Standard height
- Bottom nav: Visible
- Drawer: Over content

### Phone (Landscape)

- Grid: 2 columns (wider items)
- Toolbar: Can be hidden for more space
- Bottom nav: Hidden or compact
- Controls: Edges only

### Tablet (Portrait)

- Grid: 4-5 columns
- Toolbar: Standard
- Bottom nav: Tabs instead
- Drawer: Can be permanent on side

### Tablet (Landscape)

- Grid: 5-6 columns
- Permanent drawer on left
- Reading: Two-page spread
- Controls: More options visible

---

## Implementation Checklist

- [ ] Create theme system with predefined themes
- [ ] Implement customizable tap zones
- [ ] Create reusable book grid/list components
- [ ] Build overlay control system with auto-hide
- [ ] Implement gesture detection system
- [ ] Create TTS controls overlay
- [ ] Build settings screens with all options
- [ ] Add page turning animations
- [ ] Implement responsive layouts
- [ ] Add accessibility features
- [ ] Create navigation drawer
- [ ] Build filter and sort UI
- [ ] Add progress indicators
- [ ] Implement quick action menus

---

## Design Assets Needed

1. **Icons** (Material Icons or custom)
   - Book formats (PDF, EPUB, MOBI, etc.)
   - Actions (play, pause, bookmark, etc.)
   - Navigation (menu, back, forward, etc.)

2. **Placeholder Images**
   - Default book cover
   - Empty state illustrations
   - Error state illustrations

3. **Illustrations**
   - Onboarding screens
   - Empty library state
   - Permission requests

---

## Summary

This UI/UX design closely follows LibreraReader's patterns:
- **Information-dense** library views
- **Highly customizable** reading experience
- **Gesture-based** navigation
- **Auto-hiding** controls for distraction-free reading
- **Comprehensive** TTS controls
- **Flexible** themes and appearance options

Focus on functionality and customization over minimalism. Users should be able to tailor every aspect of their reading experience.

