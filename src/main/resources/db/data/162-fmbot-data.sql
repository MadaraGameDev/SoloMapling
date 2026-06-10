-- Bot-only account + base template character for the bot system.
-- Login: fmbot / password
--
-- BotGeneration.createBot() and getConsoleBot() load CID 2 as the template that
-- every spawned bot is cloned from, so the character id is set explicitly here.
-- The template must stay a plain character: gm = 0 (NOT an admin), level 1, and
-- no equipped items, since bots are decorated after cloning.
--
-- INSERT IGNORE: on databases that already have a CID 2 (or an 'fmbot' account)
-- from manual setup, these rows are skipped instead of failing the migration.

INSERT IGNORE INTO accounts (`name`, password, pin, pic, birthday, nxcredit, maplepoint, nxprepaid, characterslots,
                             gender, tos)
VALUES ('fmbot', '$2y$12$xS3xZTX5hSU8v0SvC4h1FewFeK4Lx0q6kXoqv/bFJu6Hr3Wuimr9q', '0000', '000000',
        '2005-05-11', 0, 0, 0, 3, 0, 1);

INSERT IGNORE INTO characters (id, accountid, world, `name`, level, exp,
                               str, dex, luk, `int`, hp, mp, maxhp, maxmp, meso, job, skincolor, gender,
                               hair, face, ap, map, spawnpoint, gm, equipslots, useslots,
                               setupslots, etcslots)
VALUES (2, (SELECT id FROM accounts WHERE `name` = 'fmbot'), 0, 'fmbot', 1, 0,
        12, 5, 4, 4, 50, 5, 50, 5, 0, 0, 0, 0,
        30030, 20000, 0, 10000, 0, 0, 96, 96,
        96, 96);
