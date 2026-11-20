package brightside.ui.shell;

import static brightside.BrightSide.APP_NAME;

/**
 * The places the sidebar leads to, in its order: Home first, then the
 * network and the private screens, with Settings at the foot (not here).
 * Each has its Lucide icon, the tooltip it carries, its shortcut digit
 * (⌘1 to ⌘9, then ⌘0), and whether it is a public (network) or a private
 * screen, which the sidebar marks with a globe or a lock as well as words.
 */
public enum Destination {
	HOME("Home", "house", "Your conversation", '1', Register.NONE),
	AGENTS("Agents", "network", "Your agents, and the conversations with each", '2', Register.NONE),
	FEED("Feed", "rss", "Posts from the agents you follow", '3', Register.PUBLIC),
	JOBS("Jobs", "scroll-text", "Everything your agents are doing and have done", '4', Register.NONE),
	INBOX("Inbox", "inbox", "Requests waiting for your decision", '5', Register.NONE),
	RECORD("Record", "file-check", "The record of what was done, and by whom", '6', Register.PRIVATE),
	PEERS("Peers", "orbit", "The people, devices, and services your " + APP_NAME + " knows", '7', Register.PUBLIC),
	SKILLS("Skills", "book-open-check", "What your assistant can help with", '8', Register.NONE),
	FILES("Files", "hard-drive", "Browse folders and personal drives", '9', Register.NONE),
	VAULT("Vault", "lock", "Secrets, access tokens, and your stored passphrase", '0', Register.PRIVATE);

	/** Network screens carry a globe; private ones a lock. Always with words, never colour alone. */
	public enum Register {
		NONE(null, null), PUBLIC("globe", "Public"), PRIVATE("lock", "Private");

		public final String icon;
		public final String word;

		Register(String icon, String word) {
			this.icon = icon;
			this.word = word;
		}
	}

	public final String label;
	public final String icon;
	public final String hint;
	/** The digit after ⌘ (Ctrl on Windows and Linux). */
	public final char key;
	public final Register register;

	Destination(String label, String icon, String hint, char key, Register register) {
		this.label = label;
		this.icon = icon;
		this.hint = hint;
		this.key = key;
		this.register = register;
	}
}
