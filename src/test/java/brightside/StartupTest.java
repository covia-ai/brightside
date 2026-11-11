package brightside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import brightside.vault.Vault;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.prim.CVMLong;
import covia.api.Fields;
import covia.venue.Config;

/**
 * Brightside's startup pass, over two real launches of one store: what the
 * first leaves wrong, the second puts right in the launch hook, before any
 * god can act.
 */
class StartupTest {

	/** Any 32 bytes are an Ed25519 seed: the same venue, and so the same gods, on each launch. */
	private static final String SEED = "5a".repeat(32);
	private static final String TASK_LLM = "v/test/ops/taskllm";
	private static final long TIMEOUT_SECONDS = 30;

	@Test
	void aRelaunchNarrowsAWidenedGodAndNotesTheUpgrade() throws Exception {
		try (StoreTestDirectory directory = new StoreTestDirectory()) {
			Path home = directory.path();
			try (EmbeddedVenue first = launch(home)) {
				Startup.Report report = first.startup();
				assertNull(report.from(), "a store never started by this pass has no versions");
				assertTrue(report.problems().isEmpty(), "with no model chosen there is nothing to check: " + report.problems());
				assertNull(first.agentRecord(first.did(), Thor.AGENT_ID), "and no god is made before one is");

				first.brightside().saveModel(TASK_LLM);
				Gods.ensureAll(first, TASK_LLM);
				// Thor as every god was stored before caps mattered, and an older build.
				first.operator().invoke("v/ops/agent/update", Maps.of(Fields.AGENT_ID, Thor.AGENT_ID,
					Fields.CONFIG, Maps.of("caps", Config.UNRESTRICTED))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
					.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
				first.brightside().saveStartupVersions(Maps.of("brightside", "0.0.1", "covia", "0.0.1"));
			}

			try (EmbeddedVenue second = launch(home)) {
				Startup.Report report = second.startup();
				assertTrue(report.upgraded(), "an older build's store is an upgrade: " + report);
				assertTrue(report.problems().isEmpty(), "and every god checks out: " + report.problems());
				ACell thor = second.agentRecord(second.did(), Thor.AGENT_ID);
				assertTrue(God.configured(thor, Thor.GOD.config(TASK_LLM)), "Thor is back to his own caps");
				assertThrows(ExecutionException.class, () -> second.engine().jobs()
					.invokeOperation("v/ops/treasury/account-list", Maps.empty(), GodContexts.of(second, Thor.AGENT_ID))
					.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "so the venue's treasury is out of his reach");
				assertTrue(God.configured(second.agentRecord(second.did(), Odin.AGENT_ID), Odin.GOD.config(TASK_LLM)),
					"Odin is as he should be, unrestricted");
				assertEquals(report.to(), second.brightside().startupVersions(), "this start's versions are recorded");
			}
		}
	}

	/** Launches the venue as Brightside does: the store keyed from the seed. */
	private static EmbeddedVenue launch(Path home) throws IOException {
		Vault.open(home, "a passphrase".toCharArray());
		AMap<AString, ACell> config = Vault.secure(AppConfig.defaultVenue(home), SEED)
			.assoc(Fields.PORT, CVMLong.create(0));
		return EmbeddedVenue.launch(config);
	}
}
