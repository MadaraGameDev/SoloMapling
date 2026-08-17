package soloMapling.plugin;

import org.gms.extension.api.HostRuntime;
import org.gms.extension.api.ServerExtension;
import org.gms.extension.api.event.ServerReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Historical SPI smoke stub only.
 *
 * <p>The <b>production</b> SoloMapling plugin for BeiDou lives in the BeiDou repo:
 * {@code BeiDou-Server/solomapling-plugin/} (full {@code soloMapling/**} + SPI).
 * Build that module and drop the jar into {@code gms-server/plugins/}.
 * See {@code beidou-plugin/README.md}.
 */
public final class SoloMaplingExtension implements ServerExtension {

    private static final Logger log = LoggerFactory.getLogger(SoloMaplingExtension.class);

    private HostRuntime runtime;

    @Override
    public String id() {
        return "solomapling";
    }

    @Override
    public String version() {
        return "0.3.0-SPI-stub";
    }

    @Override
    public void onLoad(HostRuntime runtime) {
        this.runtime = runtime;
        log.info("SoloMapling SPI stub onLoad hostId={} — use BeiDou solomapling-plugin for bots",
                runtime.hostId());

        runtime.commands().register(
                "smping",
                4,
                "SoloMapling plugin ping (SPI stub)",
                (characterId, args) -> log.info("SoloMapling !smping from characterId={} args={}",
                        characterId, String.join(" ", args == null ? new String[0] : args)));

        runtime.events().subscribe(ServerReadyEvent.class, this::onHostServerReady);
    }

    private void onHostServerReady(ServerReadyEvent event) {
        log.info("SoloMapling stub received ServerReadyEvent at {}", event.readyAtEpochMs());
    }

    @Override
    public void onServerReady() {
        log.info("SoloMapling stub onServerReady — bot world lives in BeiDou solomapling-plugin");
    }

    @Override
    public void onUnload() {
        log.info("SoloMapling stub onUnload");
        runtime = null;
    }
}
