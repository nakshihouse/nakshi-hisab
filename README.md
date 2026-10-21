# নকশি হাউস হিসাব (Nakshi Hisab) — Android shell

Thin native Android wrapper (WebView) for the Nakshi House হিসাব web app
(Google Apps Script + Sheets). All business logic lives in the web app and
updates instantly; this shell provides native voice input, file picking,
an in-app server-URL setting and self-update checks.

- Default server URL: `app/src/main/res/values/strings.xml` (`web_app_url`)
- The URL can be changed inside the installed app (Settings → সার্ভার ঠিকানা),
  so one APK can serve many deployments without a rebuild.
- Release APKs are built by GitHub Actions and published as GitHub Releases;
  the app checks Releases on launch and offers one-tap updates.

See `ANDROID_BUILD_GUIDE.md` for the manual Android Studio build.
