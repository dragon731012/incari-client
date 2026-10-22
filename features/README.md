# features

One client module per folder, each a `Module` built on `:framework` (ADR 0046). Every module but
Theme (always on) also has a **toggle key** setting, unbound by default, not repeated in each
README. How to write and add one: [`AGENTS.md`](AGENTS.md).

| Module | Category | What it does |
| --- | --- | --- |
| [1.7 Animations](oldanimations/README.md) | Visual | 1.7's block-hit swing and first-person poses |
| [Armor & Potion View](armorpotionview/README.md) | HUD | Armor durability and active effects |
| [Block Outline](blockoutline/README.md) | Visual | Your own block outline colour and width |
| [Clear Chat](clearchat/README.md) | HUD | No dark box behind chat |
| [Cooldown Indicator](cooldownindicator/README.md) | Combat | Attack cooldown as a bar, ring or percentage |
| [Coordinates](coordinates/README.md) | HUD | Position, facing, biome, other-dimension spot |
| [CPS](cps/README.md) | HUD | Clicks per second |
| [Crosshair Editor](crosshair/README.md) | Visual | A crosshair of your own design |
| [Custom Scoreboard](customscoreboard/README.md) | HUD | Place and scale the server's sidebar |
| [Enchant Glint](glintcolor/README.md) | Visual | Enchantment shimmer in your colour |
| [FPS Display](fpsdisplay/README.md) | HUD | Frames per second |
| [Freelook](freelook/README.md) | Visual | Look around without turning |
| [Fullbright](fullbright/README.md) | Visual | Caves and nights as light as day |
| [Hitboxes](hitboxes/README.md) | Combat | Entities' hitboxes in your color |
| [Hit & Kill Effects](hiteffects/README.md) | Combat | Particles on hits and kills |
| [Hit Color](hitcolor/README.md) | Visual | Hurt flash in your colour |
| [Keystrokes](keystrokes/README.md) | HUD | Keys and mouse buttons, lit while held |
| [Low Fire](lowfire/README.md) | Visual | Fire overlay drawn lower |
| [Low Shield](lowshield/README.md) | Visual | Shield drawn lower |
| [Minimap](minimap/README.md) | HUD | Live map, world map, remembered exploration |
| [No Hurt Cam](nohurtcam/README.md) | Visual | No camera tilt when hurt |
| [Old Textures](oldtextures/README.md) | Visual | Programmer Art on and off |
| [Reach Display](reachdisplay/README.md) | Combat | Your last hit's reach |
| [Saturation](saturation/README.md) | HUD | Saturation shown on the hunger bar |
| [Shield & Totem HUD](shieldtotem/README.md) | HUD | Shield durability and totem count |
| [Short Swords](shortswords/README.md) | Visual | Smaller first-person swords |
| [Shulker Tooltips](shulkertooltips/README.md) | Utility | Shulker contents as a grid |
| [Theme Editor](theme/README.md) | Visual | Client colours and font (always on) |
| [Toggle Sprint](togglesprint/README.md) | Movement | Latch sprint and sneak |
| [Waypoints](waypoints/README.md) | Utility | Marked places over the world |
| [Weather Changer](weatherchanger/README.md) | Visual | Your own weather and time of day |
| [Zoom](zoom/README.md) | Visual | Hold C to zoom in, as OptiFine's |

Test one module with `./gradlew :features:<name>:check`.
