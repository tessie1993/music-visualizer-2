# Build the MusicViz debug APK with GitHub Actions

The repository includes `.github/workflows/android.yml`.

## Build from GitHub

1. Push the repository contents to the `main` branch of `tessie1993/music-visualizer-2`.
2. Open the repository on GitHub and select **Actions**.
3. Select **Build MusicViz Debug APK**.
4. Select **Run workflow**, then run it from `main`.
5. Open the completed workflow run.
6. Under **Artifacts**, download the item named `musicviz-debug-…`.
7. Extract the downloaded ZIP. It contains:
   - `MusicViz-<version>-<commit>-debug.apk`
   - A matching `.sha256` checksum file.

The APK is Android's normal debug-signed build, so it can be installed for testing without release signing keys. Android may ask you to allow installs from the browser or file manager used to open it.

## Automatic builds

The same workflow runs automatically when Android project files are pushed to `main`, and on pull requests that change the Android project.

The APK is uploaded before unit tests, lint, and Kotlin formatting checks. This means a compilable APK remains downloadable even when a later verification check reports a problem.
