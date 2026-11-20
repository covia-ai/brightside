package brightside.ui.parity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * No platform jargon on everyday screens: no user-facing string in
 * the UI says principal, adapter, lattice, UCAN, caps, or DID, except in the developer views, where a developer asked for the
 * technical word: the inspect views ({@code brightside/ui/inspect}) and the
 * strings listed in {@code jargon-allowed.txt} (copy actions, the Developer
 * menu, and Settings → Identity → Advanced). Log lines are not shown and are
 * not checked.
 *
 * <p>Fundamental names are not jargon: session, operation, job,
 * task, venue, and agent are never banned here, and no rewording in
 * {@code reworded.txt} may drop one unless its old string is listed in
 * {@code fundamental-approved.txt}.
 */
class JargonTest {

	private static final Path UI = Path.of("src/main/java/brightside/ui");
	private static final Path DEVELOPER_VIEWS = UI.resolve("inspect");
	private static final Pattern JARGON = Pattern.compile(
		"(?i:\\b(principals?|adapters?|lattice|ucans?|caps)\\b)|\\bDIDs?\\b");
	/** The names the product is built on, which a plain-words pass must never rename on its own. */
	static final List<String> FUNDAMENTAL = List.of("session", "operation", "job", "task", "venue", "agent");
	private static final Pattern FUNDAMENTAL_NAME = Pattern.compile(
		"(?i)\\b(" + String.join("|", FUNDAMENTAL) + ")s?\\b");
	private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
	private static final Pattern LOG = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\(");

	@Test
	void noJargonOutsideTheDeveloperViews() throws IOException {
		Set<String> allowed = allowed();
		List<String> found = new ArrayList<>();
		try (Stream<Path> files = Files.walk(UI)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java") && !p.startsWith(DEVELOPER_VIEWS)).sorted().toList()) {
				// Comments out, newlines kept, so line numbers stay true.
				String code = Pattern.compile("(?s)/\\*.*?\\*/").matcher(Files.readString(f))
					.replaceAll(r -> r.group().replaceAll("[^\n]", ""));
				int n = 0;
				for (String line : code.split("\n")) {
					n++;
					if (line.strip().startsWith("//") || LOG.matcher(line).find()) continue;
					Matcher m = LITERAL.matcher(line);
					while (m.find()) {
						String s = m.group(1);
						if (!UiParityTest.userFacing(s) || allowed.contains(s)) continue;
						Matcher j = JARGON.matcher(s);
						if (j.find()) found.add(UI.relativize(f) + ":" + n + " \"" + s + "\" (" + j.group() + ")");
					}
				}
			}
		}
		assertTrue(found.isEmpty(), "jargon on everyday screens; say it plainly, or list a developer-only string in "
			+ "jargon-allowed.txt:\n  " + String.join("\n  ", found));
	}

	@Test
	void noFundamentalNameIsRewordedAway() throws IOException {
		Set<String> approved = lines("fundamental-approved.txt");
		List<String> found = new ArrayList<>();
		for (String line : lines("reworded.txt")) {
			int tab = line.indexOf('\t');
			if (tab < 0) continue;
			String old = line.substring(0, tab), now = line.substring(tab + 1);
			Set<String> lost = names(old);
			lost.removeAll(names(now));
			if (!lost.isEmpty() && !approved.contains(old)) found.add("\"" + old + "\" drops " + lost);
		}
		assertTrue(found.isEmpty(), "a fundamental name reworded away without the owner's yes; restore it, or list "
			+ "the old string in fundamental-approved.txt once approved:\n  " + String.join("\n  ", found));
	}

	@Test
	void fundamentalNamesAreNeverJargon() {
		for (String n : FUNDAMENTAL) {
			assertTrue(!JARGON.matcher(n).find() && !JARGON.matcher(n + "s").find(), n + " must not be banned");
		}
		assertTrue(!JARGON.matcher("Job ID").find(), "Job ID must not be banned");
	}

	private static Set<String> names(String s) {
		Set<String> out = new HashSet<>();
		Matcher m = FUNDAMENTAL_NAME.matcher(s);
		while (m.find()) out.add(m.group(1).toLowerCase(java.util.Locale.ROOT));
		return out;
	}

	private static Set<String> allowed() throws IOException {
		return lines("jargon-allowed.txt");
	}

	private static Set<String> lines(String resource) throws IOException {
		Set<String> out = new HashSet<>();
		try (InputStream in = JargonTest.class.getResourceAsStream(resource)) {
			if (in == null) return out;
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
			}
		}
		return out;
	}
}
