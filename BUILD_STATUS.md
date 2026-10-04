# Build status

The source tree was statically/syntactically checked in the generation environment:

- pure Kotlin positioning math, floor estimator, models, and RTT trilateration compile with the installed Kotlin compiler;
- RTT solver numeric sanity test converges on a synthetic four-anchor layout;
- Android XML resources parse successfully;
- shell bootstrap scripts pass `sh -n`;
- source ZIP passes `zip -T`.

A real APK could not be produced inside this generation runtime because it contains no Android SDK/build-tools/Gradle and outbound DNS is disabled, so the bootstrap cannot download them. No placeholder/fake `.apk` is included. On a normal Android development machine or the included GitHub Actions workflow, run `./gradlew assembleDebug`; output is `app/build/outputs/apk/debug/app-debug.apk`.
