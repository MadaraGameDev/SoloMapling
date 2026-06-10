-- Casino chip exchange shop for NPC 9000055.
-- Prices must match CasinoChipConfig.java:
--   4002000 Snail Stamp       =    10,000 mesos
--   4002001 Blue Snail Stamp  =    50,000 mesos
--   4002002 Stump Stamp       =   250,000 mesos
--   4002003 Slime Stamp       = 1,000,000 mesos

INSERT INTO shops (shopid, npcid)
VALUES (9999001, 9000055)
ON DUPLICATE KEY UPDATE npcid = 9000055;

-- shopitems has no unique key on (shopid, itemid), so clear this shop's rows
-- first to avoid duplicates on databases where the casino setup script was
-- already run manually.
DELETE FROM shopitems WHERE shopid = 9999001;

-- position = display order, lower = bottom
INSERT INTO shopitems (shopid, itemid, price, pitch, position)
VALUES (9999001, 4002000, 10000, 0, 4),
       (9999001, 4002001, 50000, 0, 3),
       (9999001, 4002002, 250000, 0, 2),
       (9999001, 4002003, 1000000, 0, 1);
