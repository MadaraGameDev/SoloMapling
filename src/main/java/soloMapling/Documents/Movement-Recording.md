# SoloMapling — Movement Recording & Pathfinding

How bots move through the world. SoloMapling supports three movement modes; this guide covers
the **recording workflow** (capturing real movement packets to replay) and the **pathfinding**
system (JGraphT directed graph + Dijkstra). Diagrams and walkthrough videos below.

> ⚙️ Implementation lives in `soloMapling/ArtificialPlayer/BotMovementSystem/`. There are 825
> pre-recorded movement files across many maps, plus three pathfinder variants
> (`pathFinderBeta`, `pathFinderAware`, `pathFinderAerial`) and a portal-based `NavigationSystem`.

## Contents
- [Path recording diagrams](#path-recording-diagrams)
- [Recording walkthroughs (video)](#recording-walkthroughs-video)
- [Pathfinder in action (video)](#pathfinder-in-action-video)

---

## Path recording diagrams

![Path recording diagram](1%20SoloMapling%20assets/SoloMapling%20Technical%20Assets/080002001%20-%20Path%20recording%20diagram.png)
*Map `080002001` — how a recorded path maps onto the map's footholds and connectors.*

![Complex path recording sample](1%20SoloMapling%20assets/SoloMapling%20Technical%20Assets/010002030%20-%20Path%20recording%20sample%20complex.png)
*Map `010002030` — a more complex recorded path with multiple platforms and connectors.*

---

## Recording walkthroughs (video)

How to capture movement recordings on the server — building the main platform path first, then
stitching connectors between platforms.

### Main platform

<video src="https://github.com/MadaraGameDev/SoloMapling/raw/main/src/main/java/soloMapling/Documents/1%20SoloMapling%20assets/SoloMapling%20Technical%20Assets/SoloMapling%20-%20Recording%20Main%20Platform.mp4" controls width="720"></video>

*Recording the main platform traversal path.*

### Connectors

<video src="https://github.com/MadaraGameDev/SoloMapling/raw/main/src/main/java/soloMapling/Documents/1%20SoloMapling%20assets/SoloMapling%20Technical%20Assets/SoloMapling%20-%20Recording%20connector%201.mp4" controls width="720"></video>

*Recording connector 1 — linking two platforms.*

<video src="https://github.com/MadaraGameDev/SoloMapling/raw/main/src/main/java/soloMapling/Documents/1%20SoloMapling%20assets/SoloMapling%20Technical%20Assets/SoloMapling%20-%20Recording%20Connector%202.mp4" controls width="720"></video>

*Recording connector 2.*

---

## Pathfinder in action (video)

The pathfinding system resolving routes across a map using the directed graph + Dijkstra.

<video src="https://github.com/MadaraGameDev/SoloMapling/raw/main/src/main/java/soloMapling/Documents/1%20SoloMapling%20assets/SoloMapling%20Technical%20Assets/SoloMapling%20Path%20finder%201%20-%20trimmed.mp4" controls width="720"></video>

*Pathfinder navigating between platforms.*

<video src="https://github.com/MadaraGameDev/SoloMapling/raw/main/src/main/java/soloMapling/Documents/1%20SoloMapling%20assets/SoloMapling%20Technical%20Assets/SoloMapling%20Path%20finder%202.mp4" controls width="720"></video>

*Pathfinder handling a more involved route.*
