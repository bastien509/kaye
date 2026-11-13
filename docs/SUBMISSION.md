# Devpost submission: Kaye

Paste each section into the matching Devpost field. Before you submit, replace or delete the parts marked **[CHECK]**.

---

## Project name
Kaye: the shop notebook that reads itself

## Elevator pitch (200 characters max)
Market vendors snap their handwritten sales notebook. Gemini Nano reads it on the phone, checks the math, and Firestore keeps the books offline. A clean monthly record, ready for a lender.

## Platform
Android + Web (Chrome)

## Links
- Web app: https://petiht.com/kaye/app.html (tap **Try it now, no account**: you get your own private notebook with sample data)
- Android APK: https://petiht.com/kaye/get-app.html
- Demo login (web and Android): `demo@petiht.com` / `KayeDemo509`. All sample names and numbers are invented.
- Code (Apache-2.0): https://github.com/bastien509/kaye
- Video: **[CHECK: YouTube link]**

---

## About the project

### Inspiration
In Cap-Haïtien's markets, the *machann* who sells rice and beans keeps her business in a school notebook: sales on one page, what neighbours took on credit on another. About 2 billion people work like her in the informal economy (ILO). Two things go wrong every week. Credit given to neighbours gets forgotten. And when a microfinance officer asks "show me your sales", there is nothing clean to show.

The notebook is also private: income, debts, the names of people who owe money. Markets often have weak signal. An app that uploads every page to a server is the wrong answer twice. On-device AI is the right one: the phone reads the page, and the cloud is used only when it adds something, with only what it needs.

### What it does
- **Snap a page.** Photograph a handwritten notebook page or a receipt. Kaye turns it into sales, costs, credit and payments, with the item, quantity, price and amount.
- **Check the math.** Every line (`qty × price = amount`) and the page total are checked. If the numbers don't add up, Kaye shows exactly which line is wrong.
- **Quick note.** "Vann 4 mamit diri 250 chak. Madan Pòl dwe 1200." becomes lines, in Kreyòl, French, English or Spanish, typed or dictated.
- **Who owes me.** A running list of customers' credit and payments, with a polite reminder message the vendor can share.
- **Month record.** Sales, costs, profit, sales by week, best sellers, money owed, and a short factual summary to show a lender (printable). Kaye never gives financial advice.
- **Works with no signal.** The notebook lives in Firestore's offline cache and syncs when signal returns. A chip shows "Waiting to sync" / "Synced".
- **Every AI result says where it ran and why**, with `inferenceSource`, the time it took and the routing reasons.

### How we built it with Firebase AI Logic
**Hybrid routing (same design on Android and in Chrome):**

| What | Mode | What leaves the device |
|---|---|---|
| Notebook photo | `ONLY_ON_DEVICE`, Gemini Nano with image input | Nothing |
| Photo Nano can't read, or the arithmetic check fails | Asks first, then `ONLY_IN_CLOUD` | That one photo, only after "Send this photo" |
| Quick note in a Nano language | `ONLY_ON_DEVICE` | Nothing |
| Kreyòl/French note, or no local model | `ONLY_IN_CLOUD` | The note with customer names and phones replaced by `C1`, `[phone]`; names are put back on the device |
| Month record | `PREFER_ON_DEVICE` / `PREFER_IN_CLOUD` by language | Totals only |
| Offline, no model | Offline rules (en/fr/ht/es) | Nothing |

**Confidence-based escalation** is the heart of it: the on-device read is verified by arithmetic, not by trust. A page whose numbers add up never needs the cloud.

**Android** (Kotlin + Jetpack Compose): `firebase-ai` + `firebase-ai-ondevice` (ML Kit Prompt API). `generativeModel(..., onDeviceConfig = OnDeviceConfig(mode, maxOutputTokens = 256))`, `content { image(bitmap); text(prompt) }`, `FirebaseAIOnDevice.checkStatus()` / `download()` with a progress bar. Because Nano on Android is validated for short outputs, the on-device prompt asks for one compact `kind|item|qty|price|amount|customer` line per entry, while the cloud gets a JSON `responseSchema`.

**Web** (Chrome): the hybrid `getGenerativeModel` with `onDeviceParams` (image input + JSON Schema `responseConstraint`) and `inCloudParams`; `initializeDeviceModel()` from a click.

**Other Firebase products, each doing real work:**
- **Cloud Firestore**: the notebook itself, offline-first on both platforms, owner-only security rules with field validation.
- **Authentication**: anonymous "Try it now" (no sign-up wall in a market), email/password, and linking an anonymous notebook to an email later.
- **Remote Config**: cloud model, on-device languages per platform, arithmetic tolerance, cloud-photo switch, max lines — changed without a new build.
- **Analytics**: `ai_call` (feature, source, ms, mode) and `entries_saved` — never entry text, names or amounts; ad signals off.
- **App Check**: Play Integrity on Android, reCAPTCHA Enterprise on the web, enforced for AI Logic.

**Stack:** Kotlin, Compose, Material 3; vanilla JS + esbuild PWA with a service worker; static hosting on petiht.com. No custom backend: Firebase is the backend.

### Challenges we ran into
- **Trusting a small model with money.** A misread "250" as "750" is worse than no answer. We made the arithmetic the judge: line math and the page total must agree, or the vendor sees exactly what's off before anything is saved.
- **Photos can't be redacted.** Text can have names replaced; a photo can't. So photos never go to the cloud silently: a clear dialog explains why and asks.
- **Short on-device output on Android.** JSON for six lines can exceed Nano's validated output length, so the device gets a compact line format and the cloud gets a schema.
- **Kreyòl.** Nano isn't validated for Kreyòl, and that's the language of the market. Offline rules understand Kreyòl instantly ("vann 3 diri 250 chak", "Madan Pòl dwe 1200"), and the cloud gets Kreyòl notes only with names replaced.
- **Sideloaded APKs and Play Integrity.** Judges install from a link, not the Play Store, so App Check verifies the app's signing certificate without requiring a Play install.

### Accomplishments that we're proud of
- Tested end to end on a real phone (Galaxy S24 Ultra) and live on the web: a Kreyòl notebook page read into 5 lines in about 3 seconds with the page total verified; a Kreyòl note with 2 customer names hidden before the cloud and restored after; a monthly record written in Kreyòl from totals only.
- **[CHECK, after testing on a Nano phone or Chrome with the Prompt API]** A notebook page read fully on-device in about X ms with nothing sent.
- No backend of our own: six Firebase products carry the whole app.

### What we learned
For money, "where the AI runs" is only half of trust; the other half is verification. Hybrid inference plus a cheap deterministic check lets the device do almost everything, and makes the cloud a rare, explained, consented exception.

### What's next for Kaye
- Pilot with vendors at Marché Cluny (Cap-Haïtien) and a microfinance partner.
- Kreyòl on-device as soon as Gemini Nano supports it (one Remote Config change).
- Shared notebooks for family businesses; a WhatsApp-ready monthly summary.

---

## Built with
firebase-ai-logic, gemini-nano, gemini, cloud-firestore, firebase-authentication, firebase-remote-config, google-analytics-for-firebase, firebase-app-check, android, kotlin, jetpack-compose, chrome-prompt-api, javascript, pwa, esbuild

## Testing instructions for judges
**Web (any browser; on-device reading needs Chrome 138+ desktop with the Prompt API and multimodal input)**
1. Open https://petiht.com/kaye/app.html and tap **Try it now, no account**. Keep "Load a sample notebook" on.
2. **Snap** → pick **Lavant + kredi (Kreyòl)** or **Page with a mistake** → **Read this page**. Without a local model, Kaye asks before sending the photo; the mistake page shows the arithmetic check.
3. **Note** → **HT · jounen mache** → **Turn into lines**. See "2 name(s) hidden" and the trace.
4. **Owed** → **Polite reminder**. **Month** → **Write the record**. **Settings** shows Remote Config values and Gemini Nano status.

**Android**
1. Install from https://petiht.com/kaye/get-app.html, tap **Try it now, no account**.
2. On a phone with AICore (Pixel 9/10, Galaxy S25…), Settings → **Get Gemini Nano**, then Snap a sample page: it is read on the phone.
3. Turn on airplane mode, write a quick note: the offline rules answer and the lines show a "waiting to sync" clock until signal returns.
