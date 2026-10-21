# নকশি হাউস হিসাব — Android অ্যাপ বিল্ড গাইড (বাংলা)

এই ফোল্ডারে (`android/`) একটি সম্পূর্ণ Android Studio প্রজেক্ট আছে — এটি ওয়েব অ্যাপটিকে আসল ইনস্টলযোগ্য Android অ্যাপে (APK) বানায়। Play Store লাগে না; ফোনে ফাইল পাঠিয়ে সরাসরি ইনস্টল (sideload) করা যায়। হোম স্ক্রিনে **"হিসাব"** নামে আইকন বসবে।

## যা যা লাগবে

- একটি কম্পিউটারে **Android Studio** (ফ্রি) — https://developer.android.com/studio
- ইন্টারনেট (প্রথমবার Gradle ডাউনলোড হবে, ~৫–১০ মিনিট — স্বাভাবিক)
- Deploy করা Web app-এর URL (আগে `DEPLOYMENT_GUIDE.md`-এর ১–৪ ধাপ শেষ করে নিন)

## ধাপ ১ — Android Studio ইনস্টল

1. উপরের লিংক থেকে Android Studio ডাউনলোড করে ইনস্টল করুন (Next → Next — ডিফল্ট সেটিংসই ঠিক)
2. প্রথমবার খুললে SDK ডাউনলোড হতে কিছুক্ষণ লাগবে — শেষ হতে দিন

## ধাপ ২ — প্রজেক্ট খোলা

1. Android Studio → **Open** → এই `android` ফোল্ডারটা সিলেক্ট করুন → OK
2. নিচে **Gradle sync** শুরু হবে — প্রথমবার ৫–১০ মিনিট লাগা স্বাভাবিক, শেষ হতে দিন
3. Sync শেষে কোনো লাল error না থাকলেই প্রজেক্ট রেডি

## ধাপ ৩ — Web app URL বসানো (গুরুত্বপূর্ণ!)

1. বামে Project প্যানেলে: `app → src → main → res → values → strings.xml` খুলুন
2. এই লাইনটা খুঁজুন:
   ```xml
   <string name="web_app_url">PASTE_WEB_APP_URL_HERE</string>
   ```
3. `PASTE_WEB_APP_URL_HERE`-এর জায়গায় Deploy করা Web app-এর পুরো URL পেস্ট করুন, যেমন:
   ```xml
   <string name="web_app_url">https://script.google.com/macros/s/AKfyc.../exec</string>
   ```
4. ফাইল সেভ করুন (Ctrl+S)

> URL না বসিয়ে বিল্ড করলে অ্যাপ খুলে "সেটআপ বাকি" লেখা দেখাবে — ভয়ের কিছু নেই, URL বসিয়ে আবার বিল্ড করলেই ঠিক।

## ধাপ ৪ — APK বিল্ড

1. মেনু: **Build → Build App Bundle(s) / APK(s) → Build APK(s)**
2. শেষ হলে নিচে ডানে notification আসবে → **locate** চাপুন
3. ফাইল: `app/build/outputs/apk/debug/app-debug.apk` — এটাই ফোনে ইনস্টল করার ফাইল

## ধাপ ৫ — ফোনে ইনস্টল

1. `app-debug.apk` ফাইলটা ফুফুর ফোনে পাঠান (WhatsApp / USB / Google Drive — যেকোনোভাবে)
2. ফোনে ফাইলটা খুলুন → **Install** চাপুন
   - "Unknown apps" / "অজানা উৎস" permission চাইলে **Allow** দিন (এটা আপনার নিজের বানানো অ্যাপ, নিরাপদ)
3. হোম স্ক্রিনে সবুজ খাতা-আইকনে **"হিসাব"** অ্যাপ পাবেন — চাপ দিলেই খুলবে

## ধাপ ৬ — প্রথমবার চালানো

1. অ্যাপ খুলুন — হিসাব ওয়েব অ্যাপটাই ভেতরে চলবে (দেখতে একই)
2. মাইক বাটন চাপলে ফোনের নিজস্ব বাংলা ভয়েস-টাইপিং খুলবে — বলুন, ছেড়ে দিন
3. ক্যামেরা বাটনে রসিদের ছবি তুললে সিস্টেমের ফাইল পিকার খুলবে
4. ড্যাশবোর্ড → মালিকের সেটিংস থেকে API key ও বাকি সেটিংস আগের মতোই

---

## ভেতরে কী কী আছে (টেকনিক্যাল নোট)

- **WebView wrapper**: `MainActivity.kt` — ফুলস্ক্রিন WebView, সব নেভিগেশন অ্যাপের ভেতরেই থাকে; Back বাটনে আগের পেজে ফেরে।
- **Voice bridge (নেটিভ)**: Android WebView-এ Web Speech API নেই — তাই ভয়েসের পথ:
  - পেজ দেখে `window.HisabApp` আছে কিনা → থাকলে `HisabApp.startVoice()` ডাকে
  - নেটিভ `RecognizerIntent` (ভাষা `bn-BD`) দিয়ে শোনে
  - ফলাফল ফেরত যায় `onNativeVoiceResult(text)` দিয়ে; ব্যর্থ হলে `onNativeVoiceError()`
- **Receipt photo**: `onShowFileChooser` সিস্টেম ফাইল পিকার খোলে — এটা না থাকলে ওয়েবের ছবি-বাটন WebView-এ নীরবে মরে যেত।
- **Offline**: নেট না থাকলে সুন্দর বাংলা "ইন্টারনেট নেই → আবার চেষ্টা করুন" স্ক্রিন।
- **Icon**: কোড-এ আঁকা vector adaptive icon (কোনো PNG নেই) — সব সাইজে ঝকঝকে।

### JS contract (ওয়েব পেজ ↔ নেটিভ অ্যাপ)

| দিক | চুক্তি |
|---|---|
| পেজ → নেটিভ | `window.HisabApp` থাকলে ভয়েসের জন্য `HisabApp.startVoice()` ডাকো (Web Speech API ব্যবহার কোরো না) |
| নেটিভ → পেজ | সফল হলে `onNativeVoiceResult(text)`; বাতিল/ব্যর্থ হলে `onNativeVoiceError()` |

`Index.html`-এ এই দুই ফাংশন আগে থেকেই আছে — অ্যাপ বানালেই ভয়েস কাজ করবে, ওয়েবে কিছু বদলাতে হবে না।

## সৎ নোট

- এটা **sideload APK** — Play Store-এ তোলা হয়নি। ফুফুর ব্যবহারের জন্য এটাই যথেষ্ট।
- Play Store-এ পাবলিশ করতে Play Console অ্যাকাউন্ট ($25) + নিজস্ব ডোমেইন লাগে — এখন scope-এর বাইরে।
- নতুন ভার্সন বানালে `versionCode` বাড়িয়ে আবার Build APK করুন; ফোনে পুরনোটার উপরেই ইনস্টল হবে, ডেটা মুছবে না (ডেটা তো Google Sheet-এ)।

## সমস্যা হলে

| সমস্যা | সমাধান |
|--------|--------|
| Gradle sync আটকে আছে | ইন্টারনেট দেখুন; প্রথমবার ১০ মিনিট পর্যন্ত লাগতে পারে |
| "SDK not found" | Android Studio → Settings → Appearance → System Settings → Android SDK → SDK Platforms-এ Android 34 (UpsideDownCake) টিক দিয়ে Apply |
| অ্যাপ খুলে "সেটআপ বাকি" | ধাপ ৩: `strings.xml`-এ আসল Web app URL বসাননি — বসিয়ে আবার Build APK |
| ফোনে "App not installed" | আগের ভার্সন থাকলে আগে uninstall করে আবার ইনস্টল করুন |
| ভয়েস খুলছে না | ফোনে Google app / Gboard থাকতে হবে (speech recognition-এর জন্য) |
| ছবি বাটনে কিছু হয় না | হওয়ার কথা নয় — file chooser নেটিভে বাঁধা; তবু হলে Android version জানান |
