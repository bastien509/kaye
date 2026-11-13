# Kaye — *Kaye ou, ki konn kalkile*

**The shop notebook that reads itself.** Market vendors snap a page of their handwritten sales notebook (or a receipt, or say a quick note). Gemini Nano reads it **on the device**, Kaye checks the arithmetic, and the books stay in sync with **Firestore, even with no signal**. At the end of the month there is a clean record to show a lender.

Built for **#BuildHybridAI 2026** on **Android** and **Web (Chrome)** with Firebase AI Logic hybrid inference.

- Web app: https://petiht.com/kaye/app.html (tap **Try it now, no account**)
- Android: https://petiht.com/kaye/get-app.html
- Demo account: `demo@petiht.com` / `KayeDemo509`. All sample names and numbers are invented.

> Kaye keeps records. It never gives financial advice and never tells anyone to borrow, spend or invest.

## The problem

About 2 billion people work in the informal economy (ILO). Most market vendors keep their books in a paper notebook, or not at all:
- money given on credit to neighbours gets forgotten;
- with no written history there is no microloan;
- notebooks hold private data — income, debts, customers' names — and markets often have poor signal.

## How hybrid inference is used

| What | Firebase AI Logic mode | What leaves the device |
|---|---|---|
| Notebook photo / receipt | `ONLY_ON_DEVICE` (Gemini Nano, image input) | Nothing |
| Photo Nano can't read, or numbers don't add up | asks first, then `ONLY_IN_CLOUD` | That one photo, only after **"Send this photo"** |
| Quick note in a Nano language (web: en/es, Android: en) | `ONLY_ON_DEVICE` | Nothing |
| Quick note in Kreyòl/French, or no local model | `ONLY_IN_CLOUD` | The note with customer names and phones replaced by `C1`, `[phone]` — names are put back on the device |
| Month record | `PREFER_ON_DEVICE` / `PREFER_IN_CLOUD` by language | Totals only, never single entries or names |
| Offline, no model | Offline rules (en/fr/ht/es) | Nothing; Firestore syncs later |

**Confidence routing:** after a photo is read on the device, Kaye checks every line (`qty × price = amount`) and the page total. Only when the check fails does it offer the cloud — and only with consent. Every result shows `inferenceSource`, the time it took and the routing reasons.

On Android, Gemini Nano is validated for short outputs (~256 tokens), so the on-device prompt asks for one compact `kind|item|qty|price|amount|customer` line per entry; the cloud gets a JSON schema.

## Firebase products

| Product | Use |
|---|---|
| **Firebase AI Logic** | Hybrid Gemini Nano + Gemini (`gemini-3.5-flash-lite`) on Android (`firebase-ai` + `firebase-ai-ondevice`) and in Chrome (Prompt API) |
| **Cloud Firestore** | The notebook itself: `users/{uid}/entries|customers|reports`, offline persistence on both platforms, owner-only security rules with field validation ([firestore.rules](firestore.rules)) |
| **Authentication** | Anonymous start ("Try it now"), email/password, and linking an anonymous notebook to an email later |
| **Remote Config** | Cloud model, on-device languages per platform, arithmetic tolerance, cloud-photo switch, max lines |
| **Analytics** | `ai_call` (feature, source, ms, mode), `entries_saved`, sign-ups — never entry text, names or amounts; ad signals off |
| **App Check** | Play Integrity on Android, reCAPTCHA Enterprise on the web; enforced for AI Logic |

## Repository layout

```
android/   Kotlin + Jetpack Compose app (Ai.kt = hybrid routing, Rules.kt = offline parser + name hiding, Repo.kt = Firestore)
client/    Web app source (ai.js, rules.js, store.js, app.js), bundled with esbuild into web/assets/kaye.js
web/       Static PWA served at petiht.com/kaye (landing, app, Android download page, service worker)
firestore.rules
```

## Run it yourself

1. Create a Firebase project; register a Web app and an Android app (`com.petiht.kaye` + your signing SHA-256).
2. Enable Authentication (Anonymous + Email/Password), Firestore, Firebase AI Logic (Gemini Developer API), Remote Config, Analytics and App Check (reCAPTCHA Enterprise key for the web, Play Integrity for Android).
3. Paste [firestore.rules](firestore.rules) into Firestore rules.
4. Web: put your config in `client/src/firebase.js`, then `cd client && npm install && node build.mjs` and serve `web/`.
5. Android: put your values in `android/app/src/main/res/values/firebase.xml`, then `./gradlew assembleRelease` (set `KAYE_KEYSTORE` / `KAYE_KEYSTORE_PASS` to sign).

## License

Apache License 2.0. See [LICENSE](LICENSE).
