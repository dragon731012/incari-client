# hitboxes: Hitboxes

Draws the box each player or mob is hit by, as F3+B does, in your own color and width. The box your
crosshair is on can have its own color. Only you see it; it shows whatever the server's debug rules.
While F3+B is on, the game's own boxes show instead.

| Setting | Kind | Default |
| --- | --- | --- |
| Color | color | `#FFFFFF` |
| Opacity | 10-100% | 100% |
| Width | 1-6 pixels | 2 |
| Target color | on/off | on |
| Target's color | color (when Target color is on) | `#FF5555` |
| Show | Players, Players and mobs, Everything | Players and mobs |
| Eye height | on/off | on |
| Look direction | on/off | on |
| Your own | on/off (third person) | off |

```sh
./gradlew :features:hitboxes:check
```
