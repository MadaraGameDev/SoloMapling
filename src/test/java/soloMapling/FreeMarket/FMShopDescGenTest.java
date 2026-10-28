package soloMapling.FreeMarket;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Party invites find their target by character name, so two bots (or a bot and a player) sharing a
// name can send an invite to the wrong character. The name pool is finite and reshuffles once it is
// exhausted, so getRandomCharacterIGN has to keep handing out new names; BotGeneration also retries
// while a name is online, which would never end if the names kept repeating.
class FMShopDescGenTest {

    @Test
    void characterNamesStayUniqueWellPastThePoolSize() {
        Set<String> seen = new HashSet<>();

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            for (int i = 0; i < 4_000; i++) {
                String name = FMShopDescGen.getRandomCharacterIGN();
                assertTrue(name.length() <= 12, "name too long for a character: " + name);
                assertTrue(seen.add(name.toLowerCase(Locale.ROOT)), "duplicate name handed out: " + name);
            }
        });
    }
}
