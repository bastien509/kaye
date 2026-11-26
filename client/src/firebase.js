// Firebase setup for Kaye: Auth, Firestore with offline persistence, App Check,
// Analytics, Remote Config and Firebase AI Logic. These web config values are public identifiers.
import { initializeApp } from 'firebase/app';
import { initializeAppCheck, ReCaptchaEnterpriseProvider } from 'firebase/app-check';
import { initializeAuth, indexedDBLocalPersistence, browserLocalPersistence } from 'firebase/auth';
import { initializeFirestore, persistentLocalCache, persistentMultipleTabManager } from 'firebase/firestore';
import { initializeAnalytics, isSupported, logEvent } from 'firebase/analytics';
import { getRemoteConfig, fetchAndActivate, getValue } from 'firebase/remote-config';
import { getAI, GoogleAIBackend } from 'firebase/ai';

export const firebaseConfig = {
  apiKey: 'AIzaSyCM2LCiwuCZjLOaUtHZYBJI5b1RjYX_vpo',
  authDomain: 'kaye-notebook.firebaseapp.com',
  projectId: 'kaye-notebook',
  storageBucket: 'kaye-notebook.firebasestorage.app',
  messagingSenderId: '811590680227',
  appId: '1:811590680227:web:c1c5525f75302a41548916',
  measurementId: 'G-N7YNCRLJZB',
};
const RECAPTCHA_SITE_KEY = '6LfkPR4uAAAAAPkZvD9Kzpb-A6o09v_0QRZBOuKm';

export const app = initializeApp(firebaseConfig);

// App Check protects Firebase AI Logic (enforced) and Firestore.
try {
  initializeAppCheck(app, { provider: new ReCaptchaEnterpriseProvider(RECAPTCHA_SITE_KEY), isTokenAutoRefreshEnabled: true });
} catch { /* already initialised */ }

// initializeAuth without a popup/redirect resolver: Kaye only uses anonymous and email sign-in,
// so it never loads apis.google.com (which the page's CSP blocks).
export const auth = initializeAuth(app, { persistence: [indexedDBLocalPersistence, browserLocalPersistence] });

// Offline-first: every read and write works with no signal and syncs when it comes back.
export const db = initializeFirestore(app, {
  localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }),
});

export const ai = getAI(app, { backend: new GoogleAIBackend() });

/** Settings that can change without a new build (Remote Config). In-app defaults first. */
export const settings = {
  cloudModel: 'gemini-3.5-flash-lite',
  deviceLanguages: ['en', 'es'],
  sumTolerance: 0.02,
  cloudPhotos: true,
  maxLines: 20,
};
const RC_DEFAULTS = {
  kaye_cloud_model: settings.cloudModel,
  kaye_device_languages_web: settings.deviceLanguages.join(','),
  kaye_sum_tolerance: String(settings.sumTolerance),
  kaye_cloud_photos_allowed: 'true',
  kaye_max_lines: String(settings.maxLines),
};

export async function loadRemoteConfig() {
  try {
    const rc = getRemoteConfig(app);
    rc.settings.minimumFetchIntervalMillis = 30 * 60 * 1000;
    rc.defaultConfig = RC_DEFAULTS;
    await fetchAndActivate(rc);
    settings.cloudModel = getValue(rc, 'kaye_cloud_model').asString() || settings.cloudModel;
    const langs = getValue(rc, 'kaye_device_languages_web').asString().split(',').map((s) => s.trim()).filter(Boolean);
    if (langs.length) settings.deviceLanguages = langs;
    const tol = getValue(rc, 'kaye_sum_tolerance').asNumber();
    if (tol > 0 && tol < 0.2) settings.sumTolerance = tol;
    settings.cloudPhotos = getValue(rc, 'kaye_cloud_photos_allowed').asBoolean();
    const max = getValue(rc, 'kaye_max_lines').asNumber();
    if (max >= 5 && max <= 40) settings.maxLines = max;
    return 'remote';
  } catch {
    return 'defaults'; // offline or blocked: in-app defaults stay
  }
}

// Analytics: counts only (where the AI ran, how long, which feature). Never entry text, names or amounts.
let analytics = null;
isSupported().then((ok) => {
  if (!ok) return;
  analytics = initializeAnalytics(app, {
    config: { allow_google_signals: false, allow_ad_personalization_signals: false, send_page_view: true },
  });
}).catch(() => {});

export function track(name, params = {}) {
  try {
    if (analytics) logEvent(analytics, name, params);
  } catch { /* never block the app for analytics */ }
}
