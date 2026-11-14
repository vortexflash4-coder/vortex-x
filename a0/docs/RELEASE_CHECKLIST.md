# Release preparation checklist

## Build & quality gates
- [ ] CI green: `assembleDebug`, `testDebugUnitTest`, `assembleDebugAndroidTest`
- [ ] Lint clean, then switch lint to blocking (`abortOnError = true`)
- [ ] `instrumented-tests.yml` passes on an API 34 emulator
- [ ] Manual matrix in `docs/TESTING.md` completed on at least: one Android 10, one Android 13, one Android 14+ device, one tablet/foldable
- [ ] No crashes in a 1-hour background playback soak with screen off

## Signing (never commit keys)
- [ ] Create upload keystore locally; store passwords in GitHub Secrets
- [ ] Add a `release` signing config that reads from environment variables
- [ ] Add a separate release workflow producing a signed AAB

## Shrinking
- [ ] `assembleRelease` with R8 succeeds; smoke-test the release build (navigation routes are kept by `proguard-rules.pro`)
- [ ] Check APK size; consider dropping `material-icons-extended` for vector assets

## Policy & store
- [ ] Data safety form: no data collected or shared; media read locally only
- [ ] Permissions justification: READ_MEDIA_AUDIO / READ_MEDIA_VIDEO (library), FOREGROUND_SERVICE_MEDIA_PLAYBACK (background music)
- [ ] Foreground service type declaration for media playback in Play Console
- [ ] No Dolby/DTS logos or trademark claims in listing or UI; format names appear only in factual diagnostics
- [ ] Privacy policy URL
- [ ] Screenshots: phone, 7" and 10" tablet

## Versioning
- [ ] Bump `versionCode` and `versionName` in `app/build.gradle.kts`
- [ ] Room: export schema committed; add migrations before any schema change (`exportSchema = true`)
- [ ] Tag the release commit

## A0 debug updates

The bundled A0 debug key is not the supplied Phase 1 signing key. Use only the original DEBUG key for a phone update and pass `.github/scripts/verify-apk.py`. Do not reuse release/upload credentials. Never uninstall or clear data to bypass a signature mismatch. See `STAGE_A0.md`.
