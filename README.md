# MouseBuster

A Bluetooth HID remote control application for Android that allows you to control a computer's mouse and keyboard from your Android device.

## Features

### Core Features

- **Bluetooth HID Connectivity** - Connect to computers via Bluetooth HID (Human Input Device) profile
- **In-App Device Scanner** - Scan for and pair with nearby Bluetooth devices directly within the app
- **Mouse Control** - Move the mouse cursor with precision
- **Keyboard Input** - Send keyboard inputs and commands, including continuous long presses
- **Touchpad Mode** - Virtual touchpad for smooth cursor movement
- **Numpad Mode** - Numeric keypad input
- **Media Controls** - Volume, mute, play/pause, next/prev track — all fully working
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

### Pairing a New Device

1. Open the app and grant Bluetooth & Location permissions when prompted
2. Tap **"Pair"** on the main screen
3. The app will automatically scan the room for nearby Bluetooth devices
4. Devices appear in the list as they are discovered — tap any device to initiate pairing
5. Approve the pairing dialogue on both devices
6. Once bonded, the HID connection is established automatically

### Connecting to an Already-Paired Device

1. Tap **"Connect"** on the main screen
2. Select your computer from the previously paired devices list
3. Connection is established automatically

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

**Media Buttons Not Working?**

- After any app update that changes the HID descriptor, Windows caches the old driver. Go to **Device Manager → Bluetooth → your phone → Uninstall device**, then reconnect. Windows will re-download the updated descriptor.
- Media keys require the `Consumer Control` HID usage page. Ensure no other Bluetooth HID driver is conflicting.

**Permission Errors on Android 12+?**

- Grant **Bluetooth** (`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`) and **Location** (`ACCESS_FINE_LOCATION`) permissions when prompted on first launch
- All permissions are requested together at startup; the app will not initialize Bluetooth until they are all granted
- If you denied permissions, go to Settings → Apps → MouseBuster → Permissions and grant them manually

**OEM Device (Oppo/Motorola) Instant Disconnect?**

- These devices use strict Bluetooth stacks (ColorOS, MyUX) that require host-initiated pairing
- Use the **Pair** button in the app and pair from your **Windows PC's Bluetooth settings**, not from the phone's native Bluetooth settings
- Ensure the Windows HID descriptor cache is cleared (see Media Buttons section above)

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

### Version 3.6 (April 3, 2026)

- **In-App Bluetooth Device Scanner** - Rebuilt the pairing flow from the ground up
  - Tap "Pair" to open an active scanner that discovers nearby Bluetooth devices in real-time
  - Devices populate the list as they are found via `BluetoothDevice.ACTION_FOUND`
  - Progress bar and status text show scanning state ("Searching..." → device count → "No devices found")
  - Lifecycle-aware: `startDiscovery()` fires on open, `cancelDiscovery()` fires on back to save battery
  - Tapping an un-paired device triggers native `createBond()` bonding, then connects automatically
- **Fixed Media Key Bitmask Alignment** - All 8 media buttons now work correctly
  - Corrected bit position mapping to exactly match the HID Report Descriptor usage ordering
  - Fixed inverted Volume Up/Down and broken Play/Pause, Mute, Next/Prev assignments
  - `MediaReport` now sends the correct 2-byte payload matching the `Report Count (16)` declaration
  - SDP subclass updated to `SUBCLASS1_COMBO` to ensure Windows installs the full composite driver
- **Fixed Startup Crash on Fresh Install** - `BluetoothAdapterWrapper.initialize()` now deferred until permissions are confirmed granted
- **Expanded Runtime Permission Handling** - All required permissions requested together at startup
  - Android 12+: `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
  - Android 11 and below: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`
- **HID Protocol Hardening** - Improved connection stability with strict Windows/OEM stacks
  - `onGetReport` / `onSetReport` callbacks now respond correctly so Windows doesn't time out the link
  - Precise `BluetoothHidDeviceAppQosSettings` values (Token Rate: 800, Latency: 11250, Delay: MAX)
  - Restored full composite HID descriptor: Mouse + Keyboard + Media Keys

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

- `BLUETOOTH` - Legacy Bluetooth access (Android 11 and below)
- `BLUETOOTH_ADMIN` - Manage Bluetooth connections and discoverability
- `BLUETOOTH_CONNECT` - Android 12+ Bluetooth connectivity
- `BLUETOOTH_SCAN` - Android 12+ device scanning and discovery
- `BLUETOOTH_ADVERTISE` - Android 12+ Bluetooth advertising
- `ACCESS_FINE_LOCATION` - Required for Bluetooth scanning (all Android versions)
- `ACCESS_COARSE_LOCATION` - Required for Bluetooth scanning (all Android versions)
- `FOREGROUND_SERVICE` - Run background service
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` - Android 12+ specific service type
- `WAKE_LOCK` - Keep device awake during AutoMouse
- `INTERNET` - Network communication
- `ACCESS_NETWORK_STATE` - Check network state

### Supported Android Versions

- Minimum SDK: 30 (Android 11)
- Target SDK: 34 (Android 14)
- Tested on: Android 11, 12, 13, 14
- Compatible with OEM skins: Oppo ColorOS, Motorola MyUX, Samsung OneUI

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
