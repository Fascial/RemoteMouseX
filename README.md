# MouseBuster

A Bluetooth HID remote control application for Android that allows you to control a computer's mouse and keyboard from your Android device.

## Features

- **Bluetooth HID Connectivity** - Connect to computers via Bluetooth HID (Human Input Device) profile.
- **In-App Device Scanner** - Scan for and pair with nearby Bluetooth devices directly within the app.
- **Mouse Control** - Move the mouse cursor with precision.
- **Touchpad Mode** - Virtual touchpad for smooth cursor movement.
- **Keyboard Input** - Send keyboard inputs and commands, including continuous long presses.
- **Media Controls** - Volume, mute, play/pause, next/prev track.
- **Auto Mouse** - Automatically move the mouse and click at configurable intervals. Runs as a background service.
- **Trackpad Recorder** - Record, playback, and save touchpad interactions. 
- **Background Operation** - The app runs seamlessly as a foreground service, allowing for continuous Bluetooth HID connection and input handling.

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

## Usage

1. Open the app and grant Bluetooth & Location permissions when prompted.
2. Tap **"Pair"** on the main screen to scan for devices.
3. Select your computer from the list to initiate pairing.
4. Once bonded, the HID connection is established automatically.
5. Use the navigation drawer to access Touchpad, Keyboard, Auto Mouse, and Trackpad Recorder modes.

## Support & Troubleshooting

- Ensure Windows has loaded the composite HID driver. 
- If you run into issues with Auto Mouse or Trackpad Recorder, ensure the connection is active. 
- Check the crash log (Menu → View Crash Log) or enable debug logging via `adb logcat | grep Mousedroid`.

## License

Proprietary - All rights reserved

## Author

Darusc
