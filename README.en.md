# FloorTrace

Native Android prototype for high-resolution personal positioning in Russia: GNSS/A-GNSS stack + raw GNSS diagnostics + inertial pedestrian dead reckoning + barometric floor estimation + OSM buildings/indoor features + best-effort NSPD cadastral overlay.

## What works

- Native Android `LocationManager` GPS + network provider (A-GNSS assistance remains inside the Android/GNSS stack).
- `GnssStatus` satellites, satellites used in fix, average C/N₀.
- `GnssMeasurementsEvent` raw GNSS measurement count for diagnostics / future pseudorange engine.
- Rotation-vector heading, barometer, step detector, magnetometer and gyroscope diagnostics.
- Wi-Fi RTT ranging on Android 9+ when the phone/APs support IEEE 802.11mc/802.11az; with 3+ surveyed AP anchors the RTT fix is trilaterated and fused into the position.
- PDR: every detected step advances the fused position by ~0.72 m only while the rotation vector and magnetic field are trustworthy. Magnetic declination and circular heading smoothing are applied, and uncertainty grows with every step.
- Robust horizontal fusion rejects stale/coarse fixes and one-off GNSS jumps (including indoor jumps between building wings). A displaced fix must form a persistent cluster before it can re-anchor the track; the displayed radius is the fused uncertainty rather than the last provider claim.
- Barometric floor estimate after entering a mapped building or manual calibration.
- OSM building and `Simple Indoor Tagging` room/corridor polygons from Overpass.
- Current-room inference only when an indoor polygon exists; confidence is reduced when horizontal GNSS uncertainty is comparable to room size.
- Cadastral overlay through an isolated NSPD adapter (`/api/geoportal/v1/intersects`) using EPSG:3857 geometry. Failure of NSPD never breaks positioning.
- Fully native custom raster/vector map view; no Google Maps SDK and no proprietary map SDK.
- OSM raster tile disk+RAM cache and proper attribution.

## Important physical limits

A stock smartphone cannot reliably identify an arbitrary room nationwide using GPS/A-GPS alone. GNSS degrades inside buildings and public floor plans are sparse. FloorTrace therefore separates **measurement** from **inference**:

1. outdoors: GNSS is the main absolute anchor;
2. during indoor transition: pressure becomes the vertical anchor;
3. indoors: step detector + orientation propagate the last good position;
4. available indoor polygons constrain/label the inferred room;
5. GNSS fixes re-anchor PDR when they become trustworthy again.

For repeatable room-level accuracy in difficult buildings, add at least one infrastructure/fingerprint source. Wi‑Fi RTT is already wired into this project; BLE/UWB, surveyed QR/NFC anchors, or a locally trained Wi‑Fi/magnetic fingerprint map are natural next providers.

### Wi-Fi RTT anchors

`app/src/main/assets/anchors.json` is intentionally empty. Add surveyed RTT-capable APs there (BSSID + WGS84 coordinate). See `anchors.example.json`. With at least three currently visible anchored APs, FloorTrace computes a local least-squares trilateration fix and mixes it into the fused track. Because this app *does* derive physical position from Wi-Fi, the Android 13+ `NEARBY_WIFI_DEVICES` permission is declared without `neverForLocation`.

## Data sources

- **OpenStreetMap** — base map, building polygons and indoor data. Attribution required: `© OpenStreetMap contributors`, ODbL.
- **OSM Overpass API** — live query of nearby building/indoor geometry. For production, host your own Overpass endpoint or cache aggressively.
- **NSPD / Rosreestr** — cadastral polygons. The code intentionally treats the current geoportal transport as a best-effort adapter, because web-portal endpoints can change.
- **Overture Maps buildings** — recommended for a production preprocessing backend/offline region packages when OSM building coverage is insufficient. Overture releases global building data in GeoParquet; it is not queried directly by this phone-only prototype.

## Build

Open the root folder in Android Studio (Quail 4 / 2026.1.4 or newer) and install Android SDK 36 + Build Tools 36.0.0.

```bash
./gradlew assembleDebug
```

APK path:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The included GitHub Actions workflow also builds the debug APK on every push/manual run.

## Production hardening checklist

- Replace `tile.openstreetmap.org` with your own tile service / PMTiles packages; the public OSM tile server is not a production CDN.
- Host your own Overpass mirror or build regional offline packages.
- Put Overture/OSM/NSPD conflation in a backend preprocessing pipeline if you need nationwide building coverage.
- Add local encrypted recording of GNSS raw + sensor events and replay tests.
- Add surveyed entrance/elevator anchors; they dramatically improve absolute floor calibration.
- Survey Wi‑Fi RTT AP coordinates for priority buildings; the RTT adapter is included but needs anchors.
- Add BLE/UWB providers for buildings where you control infrastructure.
- Use a proper EKF/UKF with phone attitude and step-length calibration for research-grade PDR.

## License

Project code: Apache-2.0. Data displayed by the app remains under the terms of the respective data provider.

## Attribution

Maintainer of Localzet contributions: **Ivan Zorin (localzet)** — <creator@localzet.com> · https://www.localzet.com. Copyright © 2026 Localzet Group. Original authorship and third-party licenses remain applicable. See [AUTHORS](.github/AUTHORS.md).
