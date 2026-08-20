/**
 * English UI strings. Flat dotted keys, one file per locale — no i18n library
 * (Must NOT Have rules one out, and two locales would not justify a dependency).
 * `{name}` placeholders are substituted by `t(key, params)`.
 */
export default {
  'app.name': 'Kefi Player',

  'tab.library': 'Library',
  'tab.settings': 'Settings',

  'library.title': 'Library',
  'library.tab.songs': 'Songs',
  'library.tab.albums': 'Albums',
  'library.tab.artists': 'Artists',
  'library.search': 'Search',
  'library.loading': 'Reading your library…',
  'library.count.songs': '{count} songs',
  'library.count.song': '1 song',
  'library.count.albums': '{count} albums',
  'library.count.artists': '{count} artists',
  'library.noResults.title': 'Nothing matches “{query}”',
  'library.noResults.body': 'Try a shorter search — accents and case are ignored.',

  'library.empty.title': 'No music found',
  'library.empty.body': 'Copy some audio files to this device, then pull to refresh.',
  'library.empty.action': 'Refresh',

  'permission.ask.title': 'Kefi needs access to your music',
  'permission.ask.body': 'Your audio files stay on the device. Nothing is uploaded anywhere.',
  'permission.ask.action': 'Allow access',
  'permission.denied.title': 'Access to music is turned off',
  'permission.denied.body':
    'Android is blocking the music permission, so the library cannot be read. Turn “Music and audio” back on in the app settings.',
  'permission.denied.action': 'Open app settings',

  'nowPlaying.title': 'Now playing',
  'nowPlaying.empty.title': 'Nothing is playing',
  'nowPlaying.empty.body': 'Pick a song from the library to get started.',
  'nowPlaying.unknownTitle': 'Unknown title',
  'nowPlaying.unknownArtist': 'Unknown artist',
  'nowPlaying.unknownAlbum': 'Unknown album',

  'player.play': 'Play',
  'player.pause': 'Pause',
  'player.next': 'Next track',
  'player.previous': 'Previous track',
  'player.shuffle': 'Shuffle',
  'player.seek': 'Seek',
  'player.repeat.off': 'Repeat off',
  'player.repeat.all': 'Repeat all',
  'player.repeat.one': 'Repeat one',
  'player.openNowPlaying': 'Open the player',

  'settings.title': 'Settings',
  'settings.language': 'Language',
  'settings.language.el': 'Ελληνικά',
  'settings.language.en': 'English',
  'settings.about': 'About',
  'settings.about.body': 'A local music player. No accounts, no network, no database.',

  'error.trackUnavailable': 'That file could not be played, so it was skipped.',
  'error.generic': 'Something went wrong.',
}
