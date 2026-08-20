/**
 * Mints YouTube PO tokens inside a WebView.
 *
 * YouTube requires a PO token to serve media URLs; without one, extraction works
 * but the transfer returns HTTP 403. The token comes from Google's BotGuard, an
 * obfuscated JS VM that has to actually run in a browser engine — which is why
 * this lives in a WebView rather than in the Kotlin or Python layers.
 *
 * The flow is split into init + mint because a GVS token is bound to a specific
 * identifier, and YouTube currently binds it to the *video ID* rather than to
 * visitor_data. One expensive BotGuard handshake therefore sets up a minter that
 * then cheaply produces a token per video.
 *
 * Loaded by PoTokenGenerator.kt into an offscreen WebView whose origin is
 * https://www.youtube.com, so these calls satisfy Google's CORS policy.
 * Built by vite.potoken.config.ts to android/app/src/main/assets/potoken/.
 */
import { BotGuardClient, getChallenge } from 'bgutils-js/botguard'
import { WebPoMinter } from 'bgutils-js/webpo'
import { buildURL, getHeaders } from 'bgutils-js/utils'
import type { WebPoSignalOutput } from 'bgutils-js/shared-types'

/** The public request key YouTube's own web player uses for BotGuard. */
const REQUEST_KEY = 'O43z0dpjhgX20SCx4KAo'

interface Session {
  minter: WebPoMinter
  visitorData: string
  ttlSeconds: number
}

let session: Session | null = null

/**
 * yt-dlp still needs visitor_data alongside the token, even when the token
 * itself is bound to the video ID.
 */
async function fetchVisitorData(): Promise<string> {
  const response = await fetch('https://www.youtube.com/', {
    credentials: 'omit',
    headers: { 'accept-language': 'en-US,en;q=0.9' },
  })
  if (!response.ok) {
    throw new Error(`youtube.com returned ${response.status} while reading visitor data`)
  }

  const html = await response.text()
  const match =
    html.match(/"VISITOR_DATA":\s*"([^"]+)"/) ?? html.match(/"visitorData":\s*"([^"]+)"/)
  if (!match) {
    throw new Error('Could not find VISITOR_DATA in the youtube.com response')
  }
  return match[1]
}

/** Exchanges a BotGuard snapshot for an integrity token. */
async function fetchIntegrityToken(botguardResponse: string): Promise<{
  integrityToken: string
  ttlSeconds: number
}> {
  const response = await fetch(buildURL('GenerateIT', false), {
    method: 'POST',
    headers: getHeaders(),
    body: JSON.stringify([REQUEST_KEY, botguardResponse]),
  })
  if (!response.ok) {
    throw new Error(`GenerateIT returned ${response.status}`)
  }

  // Protobuf-as-JSON: [integrityToken, estimatedTtlSecs, mintRefreshThreshold, fallback]
  const data = (await response.json()) as [string, number, number, string]
  if (!data?.[0]) {
    throw new Error('GenerateIT returned no integrity token')
  }
  return { integrityToken: data[0], ttlSeconds: data[1] ?? 3600 }
}

/** Runs the BotGuard handshake and caches a reusable minter. */
async function initSession(): Promise<{ visitorData: string; ttlSeconds: number }> {
  const visitorData = await fetchVisitorData()

  const challenge = await getChallenge({
    requestKey: REQUEST_KEY,
    fetchFunction: (input, init) => fetch(input as string, init),
  })

  const interpreter =
    challenge.interpreterJavascript?.privateDoNotAccessOrElseSafeScriptWrappedValue
  if (!interpreter) {
    throw new Error('BotGuard challenge did not include the interpreter script')
  }

  // Defines the VM under challenge.globalName on the window.
  new Function(interpreter)()

  const client = await BotGuardClient.create({
    program: challenge.program,
    globalName: challenge.globalName,
    globalObject: window,
  })

  // snapshot() populates this with the minter factory as a side effect.
  const webPoSignalOutput: WebPoSignalOutput = []
  const botguardResponse = await client.snapshot({ webPoSignalOutput })

  const { integrityToken, ttlSeconds } = await fetchIntegrityToken(botguardResponse)
  const minter = await WebPoMinter.create({ integrityToken }, webPoSignalOutput)

  session = { minter, visitorData, ttlSeconds }
  return { visitorData, ttlSeconds }
}

/**
 * Entry points called from Kotlin. They report through the Android JS bridge
 * rather than returning, because evaluateJavascript cannot await a promise.
 */
declare global {
  interface Window {
    __ytmp3Init: () => void
    __ytmp3Mint: (contentBinding: string) => void
    // Supplied by PoTokenGenerator.kt via addJavascriptInterface.
    AndroidPoToken?: {
      onResult: (json: string) => void
      onError: (message: string) => void
    }
  }
}

function report(promise: Promise<unknown>): void {
  promise
    .then((result) => window.AndroidPoToken?.onResult(JSON.stringify(result)))
    .catch((error: unknown) => {
      const message = error instanceof Error ? error.message : String(error)
      window.AndroidPoToken?.onError(message)
    })
}

window.__ytmp3Init = () => report(initSession())

window.__ytmp3Mint = (contentBinding: string) => {
  report(
    (async () => {
      if (!session) {
        throw new Error('PO token session is not initialised')
      }
      const poToken = await session.minter.mintAsWebsafeString(contentBinding)
      return { poToken, visitorData: session.visitorData }
    })(),
  )
}
