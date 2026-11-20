# DhakaFlix TV 📺

Android TV (প্রধানত) + মোবাইল স্ট্রিমিং অ্যাপ — DhakaFlix লোকাল সার্ভার (172.16.50.x) থেকে মুভি ও সিরিজ চালানোর জন্য।
**Made ❤️ by Israk Sisir**

## ফিচার
- Apple TV-স্টাইল UI: ফোকাস করা পোস্টার বড় হয়, পেছনের ব্যাকড্রপ বদলায়, smooth animation
- D-pad রিমোট পুরোপুরি সাপোর্ট করে, ফোনে বটম ন্যাভিগেশন
- Netflix-এর মতো একটাই লাইব্রেরি — সার্ভারের year ফোল্ডার লুকানো, নতুন আপলোড আগে
- Sort: Latest upload / Release year / A–Z / Rating · Filter: Category, Genre, Year, Movie/Series
- TMDB থেকে পোস্টার, ব্যাকড্রপ, কাহিনি, কাস্ট, রেটিং, এপিসোডের নাম ও ছবি · ভুল মিললে **Fix Match**
- একই মুভির 720p/1080p কপি এক কার্ডে, **Versions** থেকে বেছে নেওয়া যায়
- প্লেয়ার: ExoPlayer (HEVC হার্ডওয়্যার ডিকোড + FFmpeg অডিও: AC3/DTS/E-AC3) → সমস্যা হলে নিজে থেকে **LibVLC**
- অডিও ট্র্যাক বদল, সাবটাইটেল (ভেতরের + আলাদা .srt/.vtt/.ass), **সাবটাইটেল সিঙ্ক**, স্পিড, aspect, **Open in VLC**
- Continue Watching (যেখানে থেমেছিলেন সেখান থেকে), Watchlist, Mark Watched, পরের এপিসোড অটো
- Accent রং বদল, হোমের সারি চালু/বন্ধ ও ক্রম বদল, একাধিক সার্ভার/ক্যাটাগরি যোগ
- ভাষা: English · বাংলা · ঢাকাইয়া 😎

## GitHub Actions দিয়ে APK বানানো (Android Studio লাগবে না)
1. GitHub-এ একটা নতুন **private** repository বানান (যেমন `dhakaflix-tv`)।
2. এই zip-এর ভেতরের সব ফাইল সেই repo-তে আপলোড করুন (`Add file → Upload files`, `.github` ফোল্ডারসহ)।
   - কম্পিউটারে git থাকলে: `git init && git add . && git commit -m init && git branch -M main && git remote add origin <repo-url> && git push -u origin main`
3. (ঐচ্ছিক কিন্তু ভালো) **Settings → Secrets and variables → Actions → New repository secret**
   নাম `TMDB_API_KEY`, মান আপনার TMDB key। না দিলে অ্যাপের Settings → TMDB-তে key বসাতে পারবেন।
4. **Actions** ট্যাবে `Build DhakaFlix TV APK` চলবে (৮–১৫ মিনিট)। শেষ হলে **Releases**-এ APK পাবেন:
   - `DhakaFlixTV-…-armv7.apk` → **Mi TV Stick** ও বেশিরভাগ TV box
   - `DhakaFlixTV-…-arm64.apk` → নতুন ফোন
5. TV-তে ইনস্টল: TV-তে **Downloader** অ্যাপ দিয়ে Release-এর APK লিংক খুলুন, অথবা **Send Files to TV** দিয়ে ফোন থেকে পাঠান।

প্রতিবার repo-তে push করলে নতুন version নম্বরসহ APK বানবে এবং আগেরটার ওপর আপডেট হিসেবে ইনস্টল হবে (একই keystore)।

> ⚠️ `app/dhakaflix.keystore` (পাসওয়ার্ড `dhakaflix123`) সুবিধার জন্য রাখা। Repo public করলে নিজের keystore বানিয়ে
> `KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD` env দিয়ে দিন।

## Android Studio দিয়ে
আগে একবার `bash ci/prepare.sh` চালান (ফন্ট, আইকন, ব্যানার আর keystore বানায়), তারপর `File → Open` → এই ফোল্ডার → Gradle sync → `Build → Build APK(s)`।

## প্রথমবার চালানো
- অ্যাপ খুললে সার্ভার স্ক্যান করে লাইব্রেরি বানায় (প্রথমবার কয়েক মিনিট), পরে শুধু বদলানো ফোল্ডার স্ক্যান করে (প্রতি ৬ ঘণ্টায় বা Settings থেকে Refresh)।
- পোস্টার আসতে TMDB key লাগবে। key ছাড়া সার্ভারের নিজস্ব পোস্টার (যেখানে আছে) দেখাবে।
- ডিফল্ট ক্যাটাগরি: English / Hindi / South / Kolkata / Foreign / Animation মুভি + TV & WEB, Korean, Anime সিরিজ।
  Settings → Server categories থেকে চালু/বন্ধ বা নতুন URL যোগ করা যায়।

## টেক স্ট্যাক
Kotlin · Jetpack Compose · Room + Paging · OkHttp + Jsoup (h5ai parser) · kotlinx.serialization · Coil · Media3 ExoPlayer 1.9 + Jellyfin FFmpeg decoder · LibVLC 3.6 · minSdk 23 · ABI split armeabi-v7a / arm64-v8a

This product uses the TMDB API but is not endorsed or certified by TMDB.
