import { readonly, ref, watch } from 'vue'
import { Preferences } from '@capacitor/preferences'
import el from './el'
import en from './en'

/** Persisted preference key. Namespaced so it can never collide with a plugin's own keys. */
const STORAGE_KEY = 'kefi.locale'

const dictionaries = { el, en }

/** The two supported locales, in the order Settings renders them. */
export const LOCALES = ['el', 'en']

/**
 * Module-level so every component shares one locale — same reasoning as
 * `usePlayer`'s module-level refs. Seeded synchronously from the device locale so
 * the very first render is already in the right language; `initLocale()` then
 * overrides it from storage if the user has ever chosen one.
 */
const locale = ref(detectLocale())

/** @returns {'el' | 'en'} */
function detectLocale() {
  return String(navigator.language ?? 'en').toLowerCase().startsWith('el') ? 'el' : 'en'
}

/**
 * Looks a key up in the active locale, falling back to English and finally to the
 * key itself — a missing Greek string shows the English word, never a raw
 * `library.title` leaking into the UI.
 * @param {string} key
 * @param {Record<string, string | number>} [params] `{name}` placeholders to substitute.
 * @returns {string}
 */
function translate(key, params) {
  const template = dictionaries[locale.value]?.[key] ?? en[key] ?? key
  if (!params) return template
  return template.replace(/\{(\w+)\}/g, (match, name) =>
    name in params ? String(params[name]) : match,
  )
}

/**
 * Reads the stored preference, if any. Awaited before the app mounts so the user's
 * chosen language never flashes past in the other one.
 */
export async function initLocale() {
  try {
    const { value } = await Preferences.get({ key: STORAGE_KEY })
    if (value && LOCALES.includes(value)) locale.value = value
  } catch {
    // No storage (or a browser with it blocked): the detected locale stands.
  }
}

/*
 * Tells the engine which language it is casing. It matters for exactly one thing here, and
 * that thing is visible on every screen: CSS `text-transform: uppercase` on Greek keeps the
 * tonos ("ΓΛΏΣΣΑ") unless the element's language says Greek, and correct Greek drops accents
 * in capitals ("ΓΛΩΣΣΑ"). With this, the dictionaries can stay correctly spelled instead of
 * being written without accents to work around the rendering.
 */
watch(
  locale,
  (code) => {
    document.documentElement.lang = code
  },
  { immediate: true },
)

export function useI18n() {
  /** @param {'el' | 'en'} code */
  async function setLocale(code) {
    if (!LOCALES.includes(code)) return
    // Switch first, persist second: the UI must react to the tap even if the
    // write fails, and every string is a computed off this ref.
    locale.value = code
    try {
      await Preferences.set({ key: STORAGE_KEY, value: code })
    } catch {
      // Persistence is best-effort; the session still honours the choice.
    }
  }

  return { locale: readonly(locale), t: translate, setLocale }
}
