# Third-party notices

Incari Client's own code is licensed under the GNU GPL version 3 or later, with the additional
permission in [`LICENSE-EXCEPTION.txt`](LICENSE-EXCEPTION.txt). That permission covers only
Incari's code. Everything below keeps its own licence.

Minecraft is a trademark of Mojang Studios. Incari is not affiliated with or endorsed by Mojang or
Microsoft. This repository contains no Minecraft code or assets: the build tool downloads the game
from Mojang on your machine.

## In this repository

| What | Where | Licence |
| --- | --- | --- |
| [Red Hat Display](https://github.com/RedHatOfficial/RedHatFont), © The Red Hat Project Authors | `client/src/main/resources/incari-pack/assets/incari/font/` | OFL-1.1 (text beside the font) |
| [Michroma](https://github.com/googlefonts/Michroma-font), © The Michroma Project Authors | same | OFL-1.1 |
| [Inter](https://github.com/rsms/inter), © The Inter Project Authors | same, subset | OFL-1.1 |
| [Nunito](https://github.com/googlefonts/nunito), © The Nunito Project Authors | same, subset | OFL-1.1 |
| [Poppins](https://github.com/itfoundry/Poppins), © The Poppins Project Authors | same, subset | OFL-1.1 |
| [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono), © The JetBrains Mono Project Authors | same, subset | OFL-1.1 |
| "Snowy landscape in Eigergletscher, Lauterbrunnen" by Pascale Amez ([Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Snowy_landscape_in_Eigergletscher,_Lauterbrunnen.jpg)), blurred and shrunk | `client/src/main/resources/incari-pack/assets/incari/textures/gui/title_backdrop.png` | CC0-1.0 |
| Fixes to [TeaVM](https://github.com/konsoletyper/teavm) 0.16.0, © Alexey Andreev and contributors: TeaVM's own source files with Incari's changes, original headers kept | `tools/teavm-patches/src/main/java/org/teavm/` | Apache-2.0 |
| The Gradle wrapper | `gradlew`, `gradlew.bat`, `gradle/wrapper/` | Apache-2.0 |

## In a build

A build (`./incari build`) also contains, fetched at build time:

| What | Licence |
| --- | --- |
| [ViaVersion](https://github.com/ViaVersion/ViaVersion) and [ViaBackwards](https://github.com/ViaVersion/ViaBackwards), unmodified | GPL-3.0 |
| [TeaVM](https://github.com/konsoletyper/teavm)'s runtime and class library | Apache-2.0 |
| [ThreeTen Backport](https://github.com/ThreeTen/threetenbp) | BSD-3-Clause |
| [JZlib](https://github.com/ymnk/jzlib) | BSD-3-Clause |
| Minecraft: Java Edition and the libraries Mojang ships with it, from your own download | Mojang's terms and each library's own |

ViaVersion and ViaBackwards carry no exception for combining them with Minecraft, so a build that
contains them has an open licensing question ([ADR 0002](docs/adr/0002-license-gpl3-with-minecraft-exception.md)).
That is one reason the project distributes no builds, and why
[`docs/self-hosting.md`](docs/self-hosting.md) asks you never to share one.

The client lists these under Credits & Attribution (the copyright line on the title screen).

Development tools (Vineflower, Error Prone, NullAway, ESLint, Prettier, TypeScript and the other
Gradle and npm dependencies) are used to build and check the code and aren't part of a build.
