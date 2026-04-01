# MouseBuster

A Bluetooth HID remote control application for Android that allows you to control a computer's mouse and keyboard from your Android device.

## Features

### Core Features

- **Bluetooth HID Connectivity** - Connect to computers via Bluetooth HID (Human Input Device) profile
- **Mouse Control** - Move the mouse cursor with precision
- **Keyboard Input** - Send keyboard inputs and commands, including continuous long presses
- **Touchpad Mode** - Virtual touchpad for smooth cursor movement
- **Numpad Mode** - Numeric keypad input
- **Device Memory** - Save and remember paired devices

### Advanced Features

- **Auto Mouse** - Automatically move the mouse and click at configurable intervals
  - Adjustable movement speed (50-200ms intervals)
  - Configurable click intervals (500-5000ms)
  - Adjustable movement distance (20-200 pixels)
  - Optional duration timer (0 = unlimited)
  - Runs as background service with notification
  - Supports Android 12+ with proper foreground service permissions

- **Trackpad Recorder** - Record, playback, and save touchpad interactions
  - Record mouse events with precise timing and tactile haptic feedback
  - Playback recorded sequences smoothly using a scalable 1.0x speed multiplier
  - Loop playback for repeated actions
  - Save recordings to persistent storage immune to active list collisions
  - Dedicated Voice-Recorder style UI for management

- **Background Operation** - The app now runs seamlessly as a foreground service, allowing for continuous Bluetooth HID connection and input handling even when the app is minimized or the screen is turned off.
- **Connection Management** - Persistent Bluetooth connections with state tracking
- **Crash Logging** - Built-in crash logger to diagnose issues

## Building

### Prerequisites

- Android SDK 30 or higher
- Gradle 8.0+
- Java 11+

### Build Debug APK

```bash
./gradlew assembleDebug
```

The APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`

### Build Release APK

```bash
./gradlew assembleRelease
```

## Installation

1. Build the APK (see above)
2. Enable USB Debugging on your Android device
3. Connect device via USB
4. Run: `adb install app-debug.apk` or `adb install app-release.apk`

Or manually copy the APK file to your device and install through the file manager.

## Usage

### Pairing a Device

1. Open the app
2. Tap "Device List" or the add device button
3. Select your computer/device from the Bluetooth list
4. Connection will be established automatically

### Using Auto Mouse

1. Ensure you're connected to a device
2. Navigate to the Input settings (main input fragment)
3. Enable "Auto Mouse"
4. Configure settings:
   - **Move Interval**: How often to move the mouse (50-200ms)
   - **Click Interval**: How often to click (500-5000ms, default 2s)
   - **Max Distance**: Maximum pixels to move per interval (20-200px, default 80px)
   - **Duration**: How long to run (0 = infinite)
5. Auto Mouse will run in the background as a service

### Using Multimedia Controls

1. Navigate to Touchpad mode
2. Access multimedia controls in the "Multimedia Controls" section:
   - **Previous** - Skip to previous track/chapter
   - **Play/Pause** - Toggle playback
   - **Next** - Skip to next track/chapter
   - **Seek Backward (←)** - Rewind/seek backward (sends arrow left key press)
   - **Seek Forward (→)** - Fast-forward/seek forward (sends arrow right key press)
   - **Volume Down** - Decrease audio volume
   - **Mute** - Toggle mute/unmute
   - **Volume Up** - Increase audio volume
3. All buttons provide haptic feedback (vibration) and visual highlighting on press
4. Ar row seek buttons work universally with any media player that supports arrow key navigation

### Using Trackpad Recorder

1. Ensure you're connected to a device
2. Tap the burger menu and select "Trackpad Recorder"
3. Record interactions:
   - Tap "Record" to start capturing mouse/touchpad events
   - Perform the actions you want to record
   - Tap "Stop" to finish recording
4. Playback options:
   - Adjust "Playback Interval (ms)" for timing control
   - Enable "Loop Playback" to repeat automatically
   - Tap "Play" to execute the recorded sequence
5. Save recordings:
   - Tap "Save" to store the recording with a timestamp
   - Access saved recordings from the "Saved Recordings" section

### Troubleshooting

**Connection Failed?**

- Ensure Bluetooth is enabled on both devices
- Check that your computer supports Bluetooth HID profile
- Try removing and re-pairing the device

**Auto Mouse Not Working?**

- Ensure connection is active (check the connection indicator)
- Verify the move and click intervals are in valid ranges
- Check app permissions (Bluetooth, Location on Android 12+)
- Review crash logs: Tap Menu → "View Crash Log"

**Trackpad Recorder Not Saving?**

- Ensure app has proper file storage permissions
- Verify device has sufficient storage space
- Check app was not forcefully stopped
- Recordings are stored in app's internal directory

**Permission Errors on Android 12+?**

- Grant location permission (required for Bluetooth scanning)
- Grant foreground service permission when prompted
- The app requires `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission

**Connection Lost During Recording?**

- Recording captures input events locally; connection loss doesn't affect recorded data
- Playback requires active connection to send events to the paired device

## Project Structure

```
MouseBuster/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/darusc/mousedroid/
│   │   │   │   ├── services/          # Background services
│   │   │   │   │   └── AutoMouseService.kt
│   │   │   │   ├── networking/        # Bluetooth HID communication
│   │   │   │   ├── fragments/         # UI fragments
│   │   │   │   ├── mkinput/           # Input event definitions
│   │   │   │   └── MainActivity.kt
│   │   │   └── res/                   # Resources (layouts, drawables, etc)
│   │   └── test/                      # Unit tests
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── README.md
```

## Architecture

### Key Components

1. **ConnectionManager** - Singleton managing Bluetooth connections (HID-only)
2. **BluetoothConnection** - Handles HID communication via coroutines
3. **AutoMouseService** - Background service for automated mouse control
4. **InputEvent** - Defines input events including:
   - MouseMove, MouseClick, MouseScroll (mouse input)
   - KeyPress, NumpadKeyPress (keyboard input)
   - MediaEvent (media controls: Play/Pause, Next, Previous, Volume, Seek)
5. **TouchpadViewModel** - Manages touchpad input and multimedia control events
   - Multimedia buttons with 8 action types (media control and seek)
   - Vibration feedback (20ms) and visual click feedback for accessibility
6. **CrashLogger** - Persistent crash logging to SharedPreferences
7. **RecorderViewModel** - Manages recording/playback state with delta-timestamp compression
8. **Recorder Fragment** - Dedicated UI for recording interactions and saving replays
9. **RecordedEvent** - Serializable container for InputEvent + delta timing

### Threading Model

- Main thread: UI and fragment operations
- IO thread (Coroutines): Bluetooth communication
- Handler/Looper: Scheduled mouse movement and clicks in AutoMouseService

## Recent Updates

### Latest Updates

- **Background Usage Support**
  - Upgraded the app's architecture to run as a reliable foreground service.
  - Bluetooth connections are now maintained even when the app is minimized or the screen is off, enabling persistent peripheral emulation.
- **Enhanced Keyboard Engine**
  - Resolved long-press action loops in the keyboard implementation. Holding a key now correctly registers as a sustained press on the host PC. 

### Version 3.5 (April 1, 2026)

- **Stateful HID Peripheral Emulation** - Implemented a true hardware-level Bluetooth state manager
  - Deprecated generic isolated click-events in favor of a persistent `KeyboardStateManager`
  - Fully supports Multi-Touch functionality (e.g., physically holding `Ctrl` while tapping `A` and `C` simultaneously)
  - Processes complex 8-byte HID reports encapsulating Modifier masks and up to 6 simultaneous Key Scancodes
  - Installed ghosting protection: actively drops all held keys when rotating screens or opening navigation drawers
  - Implemented real-time `CapsLock` state, toggling local button styling and visually shifting alphabet labels automatically
- **Mechanical PC Keyboard Component Refactor** - Major aesthetic transformation
  - Refactored `PcKeyboardFragment` to natively incorporate navigation functions directly into the spacebar row
  - Restored authentic keycape color palettes (Light Blue modifiers, Deep Blue spacebar) eliminating background tint bleeding issues
  - Swapped generic `onClick` observers with explicit `OnTouchListener` routing for precise ACTION_DOWN / ACTION_UP hardware simulation
  - Intercepted and saved original modifier tint-states natively to prevent localized color lockups post-interaction
- **Numpad Material Modernization**
  - Eliminated bloated background drawables and migrated Numpad styles to pristine `MaterialButton` definitions
  - Bound natively to the core app background yielding a clean, transparent visual flow
  - Imposed precise `1dp` grey bounding box borders across all standard numerical keys
  - Injected an active `1.5dp` stroked cyan anchor exclusively over the central `5` key
  - Solved `MaterialButton` context collision to render Enter and Backspace as bold, solid cyan blocks

### Version 3.4 (March 30, 2026)

- **Trackpad Recorder UI/UX Modernization** - Converted UI to a premium Voice Recorder aesthetic
  - Replaced dated card-based layout with a sleek, continuous bottom sheet surface
  - Implemented a massive, centered circular `REC`/`STOP` button with prominent red bounds
  - Added native material icons (`ic_play`, `ic_pause`) for tactile playback states
  - Removed raw interval values for a true `playbackMultiplier` physics system (e.g., 1.5x) with a static `x` stepping UI
  - Real-time dynamic validation: action buttons (`Clear`, `Save`) intelligently light up when tracking events
  - Configured structural bounds ensuring the panel cleanly peaks without ever colliding with navigation toolbars
- **System Haptics Integration** - Integrated Android `Vibrator` motor feedback
  - Explicit 50ms tactile vibration "buzz" confirms exactly when a recording initializes or terminates
- **Concurrency & Architecture Stability** - Liquidated fatal loop state overlaps
  - Squashed fatal `ConcurrentModificationException` crashes by executing instant deep clones (`.toList()`, `ArrayList()`) of tracking arrays before iteration or file serialization
  - Safely anchored background sequence loops universally to `viewModelScope.launch` preventing memory leaks when views die
  - Enforced unbreakable conditional rules to seamlessly stop playback if recording is invoked, and vice-versa

### Version 3.3 (March 29, 2026)

- **AutoMouse Smooth Motion Physics** - Added velocity-based interpolation for natural movement
  - Implemented smooth velocity tracking for mouse motion (no more random jittering)
  - Linear interpolation with 0.2 easing factor for natural momentum feel
  - Periodic direction changes (~400ms) creating smooth curves instead of robotic movement
  - Better simulates human-like mouse movement patterns
- **AutoMouse UI/UX Redesign** - Major interface improvements matching Touchpad design philosophy
  - Card-based layout with organized sections for better visual hierarchy
  - Live configuration summary card showing current settings (Move interval, Click interval, Distance, Duration)
  - Preset speed buttons (Slow/Normal/Fast) with displayed values for quick configuration
  - Larger buttons (48dp) for improved touch targets and accessibility
  - Always-visible settings (no collapsible sections) for better discoverability
  - Unit labels (ms, px) for clarity on what each value represents
  - Better descriptions explaining each setting's purpose
- **Trackpad Recorder UI/UX Redesign** - Consistent design improvements
  - Card-based layout matching AutoMouse design philosophy
  - Real-time recording status summary (Events count + Recording state)
  - Recording controls card with larger 48dp buttons (Record/Play/Save/Clear)
  - Playback settings card (always visible) with improved interval control
  - Better descriptions for each section explaining functionality
  - Saved recordings card for managing recorded sequences
  - Removed collapsible sections for better content visibility
- **Code Cleanup** - Removed MediaButtonLogger debug class
  - Eliminated unnecessary debug logging overhead from multimedia controls
  - Cleaned up imports and debug code across 3 files (TouchpadViewModel, Touchpad fragment, Input fragment)
  - Simplified multimedia button handling without logging

### Version 3.2 (March 28, 2026)

- **Multimedia Controls Layout Redesign** - Fixed layout issues with media buttons
  - Replaced ChipGroup with LinearLayout for proper click handling
  - 8 media control buttons (Previous, Play/Pause, Next, Seek Backward, Seek Forward, Volume Down, Volume Up, Mute)
  - Properly responds to all button clicks with immediate visual feedback
- **Arrow Key Seek Implementation** - Added bidirectional seek controls
  - **Seek Forward (→)** button sends right arrow key press (KEY_RIGHT, 0x4F)
  - **Seek Backward (←)** button sends left arrow key press (KEY_LEFT, 0x50)
  - More universal than media events - works with any video player or media application
  - Includes vibration feedback (20ms) and visual button highlighting on click
- **Development Workflow** - VS Code integration for fast USB deployment
  - Configured Gradle tasks for rapid build-deploy cycle (~10-15 seconds)
  - One-command access via `Ctrl+Shift+B` for seamless testing

### Version 3.1 (March 28, 2026)

- **Trackpad Recorder Migration** - Moved recording feature to dedicated menu section with voice-recorder-like UX
  - Recording now in separate fragment (Router fragment) for cleaner architecture
  - Full state management via RecorderViewModel
  - Fixed data binding conflicts and property naming issues
  - Removed recording UI from standard Touchpad fragment
- **Bluetooth-Only Operation** - Removed USB and WiFi socket-based connections
  - Simplified connection architecture (Mode enum now contains only BLUETOOTH)
  - Removed TCP/UDP connection classes and related string resources
  - Improved reliability and reduced code complexity
- **Code Cleanup** - Removed unused BatteryMonitor class and related dependencies
- **AutoMouse UI Fixes** - Fixed settings visibility and duration control layout overlaps
  - Settings now always visible (not hidden until enabled)
  - Proper horizontal layout for side-by-side duration controls

### Version 3.0 (March 28, 2026)

- **Fixed AutoMouse crash on Android 12+** - Proper foreground service exception handling
- **Fixed missing permissions** - Added FOREGROUND_SERVICE_CONNECTED_DEVICE for Android 34
- **Changed service type** - From `dataSync` to `connectedDevice` (more appropriate for input devices)
- **Enhanced crash logging** - CrashLogger singleton with persistent storage
- **Added diagnostic logging** - Comprehensive debug logs for troubleshooting
- **Improved connection validation** - Connection status checks before AutoMouse starts

## Technical Details

### Permissions Required

- `BLUETOOTH` - Connect to Bluetooth devices
- `BLUETOOTH_ADMIN` - Manage Bluetooth connections
- `BLUETOOTH_CONNECT` - Android 12+ Bluetooth connectivity
- `BLUETOOTH_ADVERTISE` - Android 12+ Bluetooth advertising
- `FOREGROUND_SERVICE` - Run background service
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` - Android 12+ specific service type
- `WAKE_LOCK` - Keep device awake during AutoMouse
- `INTERNET` - Network communication
- `ACCESS_NETWORK_STATE` - Check network state

### Supported Android Versions

- Minimum SDK: 30 (Android 11)
- Target SDK: 34 (Android 14)
- Tested on: Android 11, 12, 13, 14

## Known Issues

None currently known. Report bugs with:

- Android version and device model
- Steps to reproduce
- Relevant logcat output: `adb logcat | grep Mousedroid`

## Future Enhancements

- [ ] Gesture recognition for touchpad (swipe detection, multi-touch)
- [ ] Multi-device connection support
- [ ] Custom keyboard layouts
- [ ] Performance profiling for optimal latency optimization
- [ ] Support for additional input types (gamepad, joystick)
- [ ] Recording macro editor for sequence modification
- [ ] Conditional playback (if/then logic for recordings)

## License

Proprietary - All rights reserved

## Author

Darusc

## Support

For issues, crashes, or feature requests:

1. Check the crash log (Menu → View Crash Log)
2. Enable debug logging via `adb logcat`
3. Report with:
   - Android version and device model
   - Steps to reproduce
   - Relevant logcat output
