# ADR 0060: The update notice

- Status: accepted
- Date: 2026-10-12
- Builds on: ADR 0058 (the online service)

## Context

Incari Client is published as source, and players play builds that whoever hosts them made
(`./incari build`, `docs/self-hosting.md`). Nothing told a player, or the person hosting their
build, that a newer release is out. The maintainer asked for a notice on the title screen when there is
one, linking to the public GitHub repository, with the information coming through the backend, and
for dev builds never to show it (tests aside).

The client had no version of its own until now: only the game's (26.3).

## Decision

- **The client has a version**, `incari.version` in `incari.properties` (`0.1.2` now), raised with
  each release, and the public repository's URL, `incari.repository`
  (`https://github.com/incari-client/incari-client`). `:client`'s `buildInfoSource` writes
  both into `incari.client.IncariBuild`, with `RELEASE`: whether the build is a release build
  (`-Pteavm.release`, which `./incari build` and `:app-web:webFolder` require).
- **The service names the latest release in its `hello` answer** (`release: {"version"}`,
  `docs/online-protocol.md`). Every connection says hello, signed in or not, so no extra request
  and no new connection. `OnlineService.latestRelease()` keeps the last one named, checked against
  `Releases`' form (one to four whole numbers) so nothing else the service sends reaches the screen.
- **The client compares**, and shows the notice only when the named release is newer than its own
  (`Releases.newer`). A service answering `update` (this protocol no longer served) shows it too,
  without a version.
- **The URL is the client's, not the service's.** The notice always leads to the built-in
  repository, through the game's own link confirmation (`ConfirmLinkScreen`), so a wrong or
  compromised service can't send players elsewhere.
- **Release builds only.** A dev build's version is the one being worked toward, and its developer
  doesn't need telling. Tests turn it on: `-Dincari.updates=on` on the desktop
  (`-Pincari.updates=on` for `:app-desktop:run`), `?updates=on` on the page.
  `tools/onlinedev --release <version>` names a release to test against.
- **On the title screen**, centered at the top: "Incari Client 0.2.0 is out", with a "GitHub" hint
  and a tooltip, in the menu buttons' look. It's laid out with the screen, hidden until the service
  answers, and kept clear of the small screens' links and the language and accessibility buttons.
  It isn't dismissible: it goes away when the build is updated.

## Consequences

- With no online service (`incari.online.url` empty, or unreachable), there's no notice.
- The backend has to be told the latest version at each release (its configuration), as well as
  `incari.version` being raised here. A release whose `incari.version` wasn't raised would tell its
  own players to update to what they have only if the backend were ahead of it.
- Switching between release and dev builds rewrites `IncariBuild`, so `:client` recompiles the
  classes that use it.
- Tests: `:online`'s `ReleasesTest` and `OnlineServiceTest` (`release` from hello, ill-formed ones
  ignored), `:client`'s `UpdateNoticeTest` (a dev build never shows it; newer shown, the same or
  older not). The notice was checked on the desktop against `tools/onlinedev --release 0.2.0`: shown
  at the top, its link confirmation opening the repository's URL.
