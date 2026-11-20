package brightside.ui.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.AbstractButton;
import javax.swing.JPanel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import brightside.ui.vault.VaultScreen;

/**
 * The Record is read-only: no button on it and no item in a row's menu
 * edits, removes, reverts, or undoes an entry; Export writes the rows shown; signing is shown
 * as coming, never claimed. The Vault holds Secrets, Access, and Passphrase.
 * Headless.
 */
class RecordScreenTest {

	private static final List<String> FORBIDDEN = List.of("undo", "revert", "delete", "edit", "remove", "rollback");
	private final List<String> calls = new ArrayList<>();
	private final RecordScreen screen = new RecordScreen(new RecordScreen.Host() {
		@Override public void openInJobs(String id) { calls.add("open " + id); }
		@Override public void copy(String text) { calls.add("copy " + text); }
	});
	private final RecordScreen.Row row = new RecordScreen.Row("0x01", 1_700_000_000_000L, "You", "Tau",
		"v/ops/llmagent/chat", "COMPLETE", "{\"id\":\"0x01\"}");

	@Test
	void nothingOnTheRecordUndoesOrChangesAnEntry() {
		screen.setRows(List.of(row));
		List<String> labels = new ArrayList<>();
		collect(screen, labels);
		for (var c : screen.menuFor(row).getComponents()) if (c instanceof AbstractButton b) labels.add(b.getText());
		for (String l : labels) {
			String lower = l.toLowerCase(Locale.ROOT);
			for (String word : FORBIDDEN) assertFalse(lower.contains(word), "\"" + l + "\" offers to " + word);
		}
		assertTrue(labels.contains("Open in Jobs"));
		assertTrue(labels.contains("Copy as JSON"));
	}

	@Test
	void signingIsComingNotClaimed() {
		AbstractButton verify = button(screen, "Verify signatures");
		assertFalse(verify.isEnabled());
		assertTrue(verify.getToolTipText().startsWith("Coming"));
	}

	@Test
	void exportWritesTheRowsShown(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("record.json");
		List<String> notes = new ArrayList<>();
		RecordScreen.write(file, List.of(row, row), notes::add);
		String json = Files.readString(file);
		assertTrue(json.startsWith("[") && json.trim().endsWith("]"));
		assertEquals(2, json.split("0x01", -1).length - 1, "both rows written");
		assertEquals(List.of("Exported 2 entries to record.json"), notes);
	}

	@Test
	void statusesInPlainWords() {
		assertEquals("Completed", RecordScreen.statusWord("COMPLETE"));
		assertEquals("Waiting on you", RecordScreen.statusWord("INPUT_REQUIRED"));
		assertEquals("Timed out", RecordScreen.statusWord("TIMEOUT"));
	}

	@Test
	void theVaultHoldsSecretsAccessAndPassphrase() {
		VaultScreen vault = new VaultScreen();
		vault.adopt(new JPanel(), new JPanel(), new JPanel());
		for (VaultScreen.Page p : VaultScreen.Page.values()) {
			vault.select(p);
			assertEquals(p, vault.selected());
		}
	}

	private static void collect(Component c, List<String> out) {
		if (c instanceof AbstractButton b && b.getText() != null) out.add(b.getText());
		if (c instanceof Container k) for (Component child : k.getComponents()) collect(child, out);
	}

	private static AbstractButton button(Component c, String text) {
		if (c instanceof AbstractButton b && text.equals(b.getText())) return b;
		if (c instanceof Container k) {
			for (Component child : k.getComponents()) {
				AbstractButton found = button(child, text);
				if (found != null) return found;
			}
		}
		return null;
	}
}
