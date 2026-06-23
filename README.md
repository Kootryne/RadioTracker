# RadioTracker

RadioTracker is an Android prototype that shows the FM band around your current phone location.

It does **not** use a hidden phone FM chip or raw SDR scanning. Normal Android apps do not have a universal public FM receiver/scanner API, so this app works by:

1. asking for the phone location,
2. loading a transmitter/station dataset,
3. filtering stations that are within their configured estimated reception range,
4. placing those stations on a drawn 87.5–108.0 MHz FM spectrum,
5. trying to read live `StreamTitle` metadata from configured internet radio streams.

That means the spectrum placement can be accurate only when the station dataset is accurate. The current starter dataset is focused on Stockholm FM stations and is stored in:

```text
app/src/main/assets/stations_se.json
```

## Features

- Native Android app in Kotlin.
- No AndroidX dependency needed.
- Location permission and fallback to Stockholm if GPS is denied/unavailable.
- Custom FM spectrum view from 87.5 MHz to 108.0 MHz.
- Station names are placed at their configured frequencies.
- Estimated receive filtering with distance and range in kilometers.
- Live song metadata from ICY/Shoutcast-style stream metadata when a stream URL is configured.
- GitHub Actions workflow that builds a debug APK.

## Build the APK with GitHub Actions

1. Push to `main`, or open the **Actions** tab.
2. Run the **Android APK** workflow manually if needed.
3. Open the completed workflow run.
4. Download the `RadioTracker-debug-apk` artifact.
5. Install `app-debug.apk` on your Android phone.

The debug APK is installable for testing, but it is not Play Store signed.

## Add more radio stations

Edit `app/src/main/assets/stations_se.json` and add objects like this:

```json
{
  "name": "Example FM",
  "frequencyMhz": 101.7,
  "latitude": 59.3293,
  "longitude": 18.0686,
  "rangeKm": 60,
  "streamUrl": "https://example.com/live-stream"
}
```

`streamUrl` is optional. If it is empty, the app still displays the station, but it cannot show a live song title for that station yet.

## Important limitation

For a real “every FM station my phone radio would pick up” app, you need either:

- a proper official transmitter database with coordinates, frequencies, power and antenna info, or
- real FM/SDR receiver hardware with an exposed API.

This repo is set up so the station database can be expanded later without rewriting the app UI.
