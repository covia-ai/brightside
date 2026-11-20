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
 * the UI says venue, principal, operation, adapter, lattice, UCAN, caps, job id, or
 * DID, except in the developer views, where a developer asked for the
 * technical word: the inspect views ({@code brightside/ui/inspect}) and the
 * strings listed in {@code jargon-allowed.txt} (copy actions, the Developer
 * menu, and Settings → Identity → Advanced). Log lines are not shown and are
 * not checked.
 */
class JargonTest {

	private static final Path UI = Path.of("src/main/java/brightside/ui");
	private static final Path DEVELOPER_VIEWS = UI.resolve("inspect");
	private static final Pattern JARGON = Pattern.compile(
		"(?i:\\b(venues?|venue's|principals?|operations?|adapters?|lattice|ucans?|caps|job ids?)\\b)|\\bDIDs?\\b");
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

	private static Set<String> allowed() throws IOException {
		Set<String> out = new HashSet<>();
		try (InputStream in = JargonTest.class.getResourceAsStream("jargon-allowed.txt")) {
			if (in == null) return out;
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
			}
		}
		return out;
	}
}
