# LinkVault for Android

Native Android starter built with Kotlin and Jetpack Compose.

## Included
- Dark theme is the default regardless of system appearance.
- Supabase email/password sign-in and account creation.
- Save links through the deployed `linkvault-api` Edge Function.
- Library listing and search.
- Supabase publishable key only; no service-role key or user credentials are embedded.

## Backend
- Project URL: `https://fqlmomsdzdqzamykogqm.supabase.co`
- Edge Function: `/functions/v1/linkvault-api`
- Authenticated requests use the signed-in user's Supabase access token.

## Run
1. Open this folder in Android Studio.
2. Install Android SDK 36 and JDK 17.
3. Sync Gradle and run the `app` configuration on an Android 8.0+ device/emulator.

## Scope
This is the first foundation: auth, save-link, library, and search. Offline-first sync, categories, tags, favorites UI, Android share-sheet capture, and thumbnail rendering are follow-on work.

## Security
The publishable key is intended for public/mobile clients. Never put a Supabase service-role or secret key in this app. Access is gated by Supabase Auth and backend ownership/RLS checks.

## Integration caveat
The current client expects the list endpoint to return an object containing an `items` array. If the deployed Edge Function uses a different response envelope or item field names, update `LinkVaultApi.kt` to match before release.

## GitHub Actions
The workflow at `.github/workflows/android.yml` builds a debug APK on pushes and pull requests to `main` or `master`, and supports manual runs from the Actions tab. It uploads `app-debug.apk` as a 14-day workflow artifact. The workflow uses JDK 17, Gradle 8.13, and Android SDK 36.
