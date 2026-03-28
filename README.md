# MouseBuster

A Bluetooth HID remote control application for Android that allows you to control a computer's mouse and keyboard from your Android device.

## Features

### Core Features

- **Bluetooth HID Connectivity** - Connect to computers via Bluetooth HID (Human Input Device) profile
- **Mouse Control** - Move the mouse cursor with precision
- **Keyboard Input** - Send keyboard inputs and commands
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
  - Record mouse events with precise timing
  - Playback recorded sequences with adjustable interval
  - Loop playback for repeated actions
  - Save recordings to persistent storage
  - Dedicated UI for recording management

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
4. **InputEvent** - Defines mouse/keyboard events (MouseMove, MouseClick, KeyboardPress, MediaEvent)
5. **CrashLogger** - Persistent crash logging to SharedPreferences
6. **RecorderViewModel** - Manages recording/playback state with delta-timestamp compression
7. **Recorder Fragment** - Dedicated UI for recording interactions and saving replays
8. **RecordedEvent** - Serializable container for InputEvent + delta timing

### Threading Model

- Main thread: UI and fragment operations
- IO thread (Coroutines): Bluetooth communication
- Handler/Looper: Scheduled mouse movement and clicks in AutoMouseService

## Recent Updates

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
