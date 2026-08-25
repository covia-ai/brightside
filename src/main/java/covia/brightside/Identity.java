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
 * <p>Two forms of the name, deliberately kept apart:</p>
 * <ul>
 *   <li>the <b>display name</b> — exactly what the person typed (case and
 *       spacing preserved), e.g. {@code "Mike"}. This is what the UI shows and
 *       what the assistant calls them.</li>
 *   <li>the <b>slug</b> — a lower-case, DID-safe label, e.g. {@code "mike"},
 *       used only to build the venue principal {@code <venueDID>:u:mike}.</li>
 * </ul>
 *
 * <p>The DID follows the suffix convention Covia uses for the anonymous
 * {@code <venueDID>:public} principal and the {@code :u:} user segment of
 * {@code did:web} venues, so the agent's turns are attributed to their owner.
 * Persisted on its own in {@code <home>/identity.json}, apart from the
 * hand-edited {@code config.json}.
 */
public final class Identity {

	private static final Logger log = LoggerFactory.getLogger(Identity.class);

	/** DID segment introducing a named user sub-principal (mirrors {@code :public}). */
	public static final String USER_SEP = ":u:";

	/** Where the chosen name lives, under the BrightSide data directory. */
	public static final String FILE_NAME = "identity.json";

	private static final int MAX_LENGTH = 60;

	private final String display;
	private final String slug;

	private Identity(String display, String slug) {
		this.display = display;
		this.slug = slug;
	}

	/**
	 * Creates an identity from a name as typed. The display name keeps its case
	 * and spacing; the slug is normalised to a DID-safe label.
	 *
	 * @throws IllegalArgumentException if nothing usable is left once normalised
	 */
	public static Identity of(String rawName) {
		String slug = sanitise(rawName);
		if (slug.isEmpty()) throw new IllegalArgumentException("A user name is required");
		String display = normaliseDisplay(rawName);
		if (display.isEmpty()) display = slug;
		return new Identity(display, slug);
	}

	/** The name as the person typed it — what the UI and the assistant use. */
	public String name() {
		return display;
	}

	/** The DID-safe lower-case label (e.g. {@code "mike"}). */
	public String slug() {
		return slug;
	}

	/** The technical label shown only in About (e.g. {@code "u:mike"}). */
	public String label() {
		return "u:" + slug;
	}

	/** This user's DID on the given venue: {@code <venueDID>:u:<slug>}. */
	public String userDID(String venueDID) {
		return venueDID + USER_SEP + slug;
	}

	/** Trims and collapses whitespace, preserving case; caps the length. */
	public static String normaliseDisplay(String raw) {
		if (raw == null) return "";
		String s = raw.strip().replaceAll("\\s+", " ");
		return (s.length() > MAX_LENGTH) ? s.substring(0, MAX_LENGTH).strip() : s;
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
		for (int i = 0; i < lower.length() && sb.length() < MAX_LENGTH; i++) {
			char c = lower.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '_') {
				sb.append(c);
				lastSep = false;
			} else if (!lastSep && sb.length() > 0) {
				// collapse any other character (spaces, ':', '/', accents, …) to one '-'
				sb.append('-');
				lastSep = true;
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

	/** A first-launch suggestion from the OS user name, or {@code "You"}. */
	public static String suggestName() {
		String suggestion = normaliseDisplay(System.getProperty("user.name", ""));
		return suggestion.isEmpty() ? "You" : suggestion;
	}

	/** Reads the saved identity, or {@code null} if none has been chosen yet. */
	public static Identity load(Path home) {
		Path file = home.resolve(FILE_NAME);
		if (!Files.exists(file)) return null;
		try {
			AMap<AString, ACell> map = RT.ensureMap(JSON.parseJSON5(Files.readString(file)));
			AString n = (map == null) ? null : RT.ensureString(map.get(Strings.create("name")));
			if (n == null) return null;
			String raw = n.toString();
			return sanitise(raw).isEmpty() ? null : of(raw);
		} catch (Exception e) {
			log.warn("Could not read {} — will ask for a name again: {}", file, e.toString());
			return null;
		}
	}

	/** Persists this identity (the display name) to {@code <home>/identity.json}. */
	public void save(Path home) throws IOException {
		Files.createDirectories(home);
		AMap<AString, ACell> map = Maps.of(Strings.create("name"), Strings.create(display));
		Files.writeString(home.resolve(FILE_NAME), JSON.printPretty(map).toString() + "\n");
	}

	@Override
	public boolean equals(Object o) {
		return (o instanceof Identity other) && slug.equals(other.slug) && display.equals(other.display);
	}

	@Override
	public int hashCode() {
		return 31 * slug.hashCode() + display.hashCode();
	}

	@Override
	public String toString() {
		return display + " (" + label() + ")";
	}
}
