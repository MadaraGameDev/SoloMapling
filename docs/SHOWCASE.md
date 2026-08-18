# SoloMapling — Showcase

A visual tour of the artificial-player simulation framework: hundreds of autonomous
server-driven bots that populate a Global MapleStory v83 world as if real players were present.

> 📺 **Want it in motion?** See the [Movement Recording guide](Movement-Recording.md) for the
> pathfinding/recording videos, and the [Dev Diary](DEV-DIARY.md) for work-in-progress shots.

## Contents
- [Free Market simulation](#free-market-simulation)
- [Blackjack](#blackjack)
- [Drop Game](#drop-game)
- [Dialogue system](#dialogue-system)
- [Henesys social & jump-quest bots](#henesys-social--jump-quest-bots)
- [OPQ Rush](#opq-rush)
- [Chalkboard](#chalkboard)
- [NPC & portal blocking](#npc--portal-blocking)

---

## Free Market simulation

Bots run `HiredMerchant` and `PlayerShop` instances across the Henesys / Ludibrium / Perion /
Elnath Free Market rooms, with procedurally generated inventories and region-aware pricing.

![Free Market overview](1%20SoloMapling%20assets/FM%201.png)
*Free Market room populated with bot-run shops.*

![Free Market shopfront](1%20SoloMapling%20assets/FM%201A.png)
*A bot hired-merchant storefront up close.*

![Free Market browsing](1%20SoloMapling%20assets/FM%202.png)
*Browsing a bot shop's procedurally generated inventory.*

![Free Market offer response](1%20SoloMapling%20assets/FM%202a%20offer%20response.png)
*A bot responding to a buy offer.*

![Free Market 3](1%20SoloMapling%20assets/FM%203.png)
*Free Market simulation in action.*

![Free Market 4](1%20SoloMapling%20assets/FM%204.png)
*Free Market simulation in action.*

![Free Market 5](1%20SoloMapling%20assets/FM%205.png)
*Free Market simulation in action.*

![Free Market 6](1%20SoloMapling%20assets/FM%206.png)
*Free Market simulation in action.*

![Free Market 7](1%20SoloMapling%20assets/FM%207.png)
*Free Market simulation in action.*

![Free Market 8](1%20SoloMapling%20assets/FM%208.png)
*Free Market simulation in action.*

![Free Market 9](1%20SoloMapling%20assets/FM%209.png)
*Free Market simulation in action.*

![Free Market 10](1%20SoloMapling%20assets/FM%2010.png)
*Free Market simulation in action.*

![Free Market 11](1%20SoloMapling%20assets/FM%2011.png)
*Free Market simulation in action.*

![Free Market 12](1%20SoloMapling%20assets/FM%2012.png)
*Free Market simulation in action.*

![Free Market 13](1%20SoloMapling%20assets/FM%2013.png)
*Free Market simulation in action.*

---

## Blackjack

A complete card game hosted by the GameZoneHostBot — dealer AI, betting, and card visuals.

![Blackjack 1](1%20SoloMapling%20assets/blackjack%201.png)
*Blackjack table hosted by a dealer bot.*

![Blackjack 2](1%20SoloMapling%20assets/blackjack%202.png)
*A hand in progress.*

![Blackjack 3](1%20SoloMapling%20assets/blackjack%203.png)
*Card visuals dealt to players.*

![Blackjack 4](1%20SoloMapling%20assets/blackjack%204.png)
*Resolving the round.*

---

## Drop Game

An interactive minigame where a bot drops items and players race to loot them, driven by a YAML prize pool.

![Drop Game 1](1%20SoloMapling%20assets/dg%201.png)
*Drop Game in progress.*

![Drop Game 2](1%20SoloMapling%20assets/dg%202.png)
*Players racing for dropped prizes.*

![Drop Game 3](1%20SoloMapling%20assets/dg3.png)
*Prize payout.*

---

## Dialogue system

YAML-driven NPC-style dialogue with randomized lines, emotes, and variable substitution.

![Dialogue 1](1%20SoloMapling%20assets/dialog%201.png)
*Bot dialogue interaction.*

![Dialogue 2](1%20SoloMapling%20assets/dialog%202.png)
*Bot dialogue interaction.*

![Dialogue 3](1%20SoloMapling%20assets/dialog%203.png)
*Bot dialogue interaction.*

![Dialogue 4](1%20SoloMapling%20assets/dialog%204.png)
*Bot dialogue interaction.*

![Dialogue 5](1%20SoloMapling%20assets/dialog%205.png)
*Bot dialogue interaction.*

![Dialogue 6](1%20SoloMapling%20assets/dialog%206.png)
*Bot dialogue interaction.*

---

## Henesys social & jump-quest bots

Bots loitering, chatting, and running the Henesys jump quest to make the world feel alive.

![Henesys social bots](1%20SoloMapling%20assets/Hene%20social%20bots.png)
*Social bots gathered in Henesys.*

![Henesys JQ bots](1%20SoloMapling%20assets/Henesys%20JQ%20Bots%202.png)
*Bots running the Henesys jump quest.*

![Henesys JQ pots](1%20SoloMapling%20assets/Henesys%20JQ%20Pots.png)
*Henesys jump-quest activity.*

---

## OPQ Rush

A multi-phase Orbis PQ simulation: recruitment, party formation, and staged clears.

![OPQ Rush stage 1](1%20SoloMapling%20assets/opq%20rush%20stage%201.png)
*OPQ Rush — stage 1.*

![OPQ Rush stage 2](1%20SoloMapling%20assets/opq%20rush%20stage%202.png)
*OPQ Rush — stage 2.*

---

## Chalkboard

![Chalkboard 0](1%20SoloMapling%20assets/chalkboard%200.png)
*Chalkboard feature.*

![Chalkboard 1](1%20SoloMapling%20assets/chalkboard%201.png)
*Chalkboard feature.*

![Chalkboard example 1](1%20SoloMapling%20assets/chalkboard%20example%201.png)
*Chalkboard in use.*

![Chalkboard example 2](1%20SoloMapling%20assets/chalkboard%20example%202.png)
*Chalkboard in use.*

---

## NPC & portal blocking

Logic that keeps bots from clustering on NPCs and portals.

![NPC block default](1%20SoloMapling%20assets/npc%20block%20-%20default.png)
*Default NPC state.*

![NPC block blocked](1%20SoloMapling%20assets/npc%20block%201%20-%20blocked.png)
*A blocked NPC.*

![NPC block code](1%20SoloMapling%20assets/npc%20block%20code.png)
*The NPC-blocking logic.*

![Portal block 0](1%20SoloMapling%20assets/nPortal%20block%200.png)
*Portal blocking — default.*

![Portal block 1](1%20SoloMapling%20assets/nPortal%20block%201.png)
*Portal blocking — engaged.*
