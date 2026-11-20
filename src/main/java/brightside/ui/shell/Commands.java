package brightside.ui.shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.swing.KeyStroke;

/**
 * One registry of everything the shell can do: each sidebar destination,
 * Settings page, menu item, and action, with where it lives and its shortcut.
 * The menu bar and the command palette are both built from it, so if a
 * control exists the palette can reach it, and the reachability tests read it.
 */
public final class Commands {

	/**
	 * A command.
	 *
	 * @param id       stable id, for tests and recents
	 * @param name     what the palette and the menu show
	 * @param where    where it lives in the layout ("Settings → General → Advanced"), which the palette shows
	 * @param menu     the menu it sits in (Tau, File, Edit, View, Developer, Window, Help), or null for palette only
	 * @param key      its shortcut, or null
	 * @param aliases  other names it answers to in the palette (old names of moved pages)
	 * @param action   what it does
	 */
	public record Command(String id, String name, String where, String menu, KeyStroke key, List<String> aliases,
			Runnable action) {

		/** How well {@code query} matches: 0 for no match, higher is better. */
		public int score(String query) {
			String q = query.strip().toLowerCase(Locale.ROOT);
			if (q.isEmpty()) return 1;
			int best = match(name, q);
			for (String a : aliases) best = Math.max(best, match(a, q) - 1);
			best = Math.max(best, match(where, q) / 2);
			return best;
		}

		private static int match(String text, String q) {
			if (text == null) return 0;
			String t = text.toLowerCase(Locale.ROOT);
			if (t.equals(q)) return 100;
			if (t.startsWith(q)) return 80;
			if (t.contains(" " + q)) return 60;
			if (t.contains(q)) return 40;
			// In order, not necessarily together: "nwc" finds "New chat".
			int i = 0;
			for (char c : t.toCharArray()) if (i < q.length() && c == q.charAt(i)) i++;
			return (i == q.length()) ? 10 : 0;
		}
	}

	private final List<Command> all = new ArrayList<>();

	public Commands add(String id, String name, String where, String menu, KeyStroke key, Runnable action,
			String... aliases) {
		for (Command c : all) if (c.id().equals(id)) throw new IllegalArgumentException("Duplicate command " + id);
		all.add(new Command(id, name, where, menu, key, List.of(aliases), action));
		return this;
	}

	public List<Command> all() {
		return Collections.unmodifiableList(all);
	}

	public Command get(String id) {
		for (Command c : all) if (c.id().equals(id)) return c;
		return null;
	}

	/** The commands in {@code menu}, in the order added. */
	public List<Command> menu(String menu) {
		return all.stream().filter(c -> menu.equals(c.menu())).toList();
	}

	/** The commands matching {@code query}, best first, ties in registry order. */
	public List<Command> search(String query) {
		List<Command> hits = new ArrayList<>();
		for (Command c : all) if (c.score(query) > 0) hits.add(c);
		hits.sort((a, b) -> Integer.compare(b.score(query), a.score(query)));
		return hits;
	}
}
