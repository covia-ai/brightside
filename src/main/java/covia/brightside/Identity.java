package covia.brightside;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * The local user the chat window acts as.
 *
 * <p>On a self-sovereign local venue the user is a named sub-principal of the
 * venue: DID {@code <venueDID>:u:<name>} — the same suffix convention Covia
 * uses for the anonymous {@code <venueDID>:public} principal and for the
 * {@code :u:} user segment of {@code did:web} venues. So "mike" becomes a real
 * venue principal {@code did:key:…:u:mike}, its agents live at
 * {@code did:key:…:u:mike/g/<agentId>}, and the venue attributes its turns as
 * coming from the agent's own owner.
 *
 * <p>Persisted on its own in {@code <home>/identity.json} — separate from the
 * hand-edited {@code config.json} so choosing a name never rewrites the user's
 * commented configuration.
 */
public final class Identity {

	private static final Logger log = LoggerFactory.getLogger(Identity.class);

	/** DID segment introducing a named user sub-principal (mirrors {@code :public}). */
	public static final String USER_SEP = ":u:";

	/** Where the chosen name lives, under the BrightSide data directory. */
	public static final String FILE_NAME = "identity.json";

	private static final int MAX_NAME_LENGTH = 64;

	private final String name;

	private Identity(String name) {
		this.name = name;
	}

	/**
	 * Creates an identity from a display name, normalising it to a single DID-safe
	 * label ({@code a-z}, {@code 0-9}, {@code . _ -}).
	 *
	 * @throws IllegalArgumentException if the name is empty once normalised
	 */
	public static Identity of(String rawName) {
		String clean = sanitise(rawName);
		if (clean.isEmpty()) throw new IllegalArgumentException("A user name is required");
		return new Identity(clean);
	}

	/** The normalised, DID-safe user name (e.g. {@code "mike"}). */
	public String name() {
		return name;
	}

	/** How the user is shown in the UI (e.g. {@code "u:mike"}). */
	public String label() {
		return "u:" + name;
	}

	/** This user's DID on the given venue: {@code <venueDID>:u:<name>}. */
	public String userDID(String venueDID) {
		return venueDID + USER_SEP + name;
	}

	/**
	 * Normalises a name to a single DID-safe label: lower-case, with any run of
	 * other characters turned into {@code -}, trimmed of leading/trailing
	 * separators and capped in length. Returns {@code ""} when nothing usable
	 * is left (so the caller can fall back to a suggestion).
	 */
	public static String sanitise(String raw) {
		if (raw == null) return "";
		String lower = raw.trim().toLowerCase(Locale.ROOT);
		StringBuilder sb = new StringBuilder(lower.length());
		boolean lastSep = false;
		for (int i = 0; i < lower.length() && sb.length() < MAX_NAME_LENGTH; i++) {
			char c = lower.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_') {
				sb.append(c);
				lastSep = false;
			} else {
				// collapse any other character (spaces, ':', '/', accents, …) to one '-'
				if (!lastSep && sb.length() > 0) {
					sb.append('-');
					lastSep = true;
				}
			}
		}
		int end = sb.length();
		while (end > 0 && isSep(sb.charAt(end - 1))) end--;
		int start = 0;
		while (start < end && isSep(sb.charAt(start))) start++;
		return sb.substring(start, end);
	}

	private static boolean isSep(char c) {
		return c == '-' || c == '.' || c == '_';
	}

	/** A first-launch suggestion from the OS user name, or {@code "user"}. */
	public static String suggestName() {
		String suggestion = sanitise(System.getProperty("user.name", ""));
		return suggestion.isEmpty() ? "user" : suggestion;
	}

	/** Reads the saved identity, or {@code null} if none has been chosen yet. */
	public static Identity load(Path home) {
		Path file = home.resolve(FILE_NAME);
		if (!Files.exists(file)) return null;
		try {
			AMap<AString, ACell> map = RT.ensureMap(JSON.parseJSON5(Files.readString(file)));
			AString n = (map == null) ? null : RT.ensureString(map.get(Strings.create("name")));
			if (n == null) return null;
			String clean = sanitise(n.toString());
			return clean.isEmpty() ? null : new Identity(clean);
		} catch (Exception e) {
			log.warn("Could not read {} — will ask for a name again: {}", file, e.toString());
			return null;
		}
	}

	/** Persists this identity to {@code <home>/identity.json}. */
	public void save(Path home) throws IOException {
		Files.createDirectories(home);
		AMap<AString, ACell> map = Maps.of(Strings.create("name"), Strings.create(name));
		Files.writeString(home.resolve(FILE_NAME), JSON.printPretty(map).toString() + "\n");
	}

	@Override
	public boolean equals(Object o) {
		return (o instanceof Identity other) && name.equals(other.name);
	}

	@Override
	public int hashCode() {
		return name.hashCode();
	}

	@Override
	public String toString() {
		return label();
	}
}
