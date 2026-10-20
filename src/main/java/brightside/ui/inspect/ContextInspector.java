package brightside.ui.inspect;

import static brightside.ui.inspect.Blocks.raw;

import java.awt.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTabbedPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.AgentContext;
import brightside.SessionHistory;
import brightside.SkillIndex;
import brightside.ui.components.Badge;
import brightside.ui.components.Card;
import brightside.ui.components.Labels;
import brightside.ui.components.Readout;
import brightside.ui.components.Scrolls;
import brightside.ui.components.StatTile;
import brightside.ui.components.Styles;
import brightside.ui.components.Tone;
import net.miginfocom.swing.MigLayout;

/**
 * Read-only views of the exact context an agent sends its model (from
 * {@link AgentContext}), as tabs for a {@link JTabbedPane}: <em>Overview</em>
 * (model, budget, counts), then the pieces of the context — <em>Messages</em>
 * (every assembled message, by band: head, live surface, conversation, tool
 * loop, tail), <em>Cycle detail</em> (the stored turns), <em>Tools</em> (every
 * definition the model receives, by where it comes from), <em>Skills</em> (the
 * skills loaded in this conversation, each linking to what it reveals in the
 * library), <em>Loaded</em> (the context entries and their accounting) and
 * <em>Raw</em> (the untouched report). {@link SessionViews} shows them above
 * the chat, the Overview beside the chat and the pieces as one Context tab's
 * own tabs; every view is the conversation's own situation, and the whole
 * skill library is the Skills screen's.
 *
 * <p>The Overview is the conversation at a glance: the model and its budget as
 * a meter, and a tile for each piece that presses through to its tab. Every
 * other view is a {@link Readout}: one document of sections and entries,
 * long content folded behind a "Show all" link, everything shown selectable
 * and copyable. The views cross-refer: a tool to the skill that declares it, a
 * skill to what it reveals and the tools it grants, a call to its result, a
 * loaded entry to its skill — each a link that selects the tab holding the
 * target document and brings the detail into view. Terms explain themselves
 * on hover: a band, a role, a load's kind or status, a tool's source.
 */
public final class ContextInspector {

	// What the terms mean, for the tooltips. Each is one plain sentence or two.
	private static final String TIP_MODEL = "The model operation the assistant's replies run through.";
	private static final String TIP_BUDGET = "The model's declared context size, and how much of it this assembly uses. "
		+ "A guide the assembler warns against, not a cap.";
	private static final String TIP_TOKENS = "Tokens the model provider has counted for this conversation so far: "
		+ "in, out, and read from or written to the provider's cache.";
	private static final String TIP_MESSAGES = "Every message the model receives, in order, by band.";
	private static final String TIP_TOOLS = "Every tool definition the model receives.";
	private static final String TIP_LOADED = "The context entries loaded for this conversation, and their accounting.";
	private static final String TIP_META_CYCLE = "The cycle that produced the turn: its source, the model's finish "
		+ "reason, its tokens and its job.";
	private static final String TIP_GRANTS = "Tools the skill grants while it is loaded.";
	private static final String TIP_REVEALS = "Skills and directories that enter the index once this one is loaded.";
	private static final String TIP_PATH = "Where the skill lives on the venue.";
	private static final String TIP_ACCOUNTING = "What the entry put into the context on this assembly, against the "
		+ "most it may: content past the budget is cut and the entry marked truncated. An entry the cached "
		+ "prefix already holds is left as it is and not measured again.";
	private static final String TIP_UNAVAILABLE = "Configured tools that did not resolve to an operation the agent "
		+ "may call.";

	/** One view and the title of its tab (with a count where one is telling). */
	public record Tab(String title, JComponent view) {
	}

	/**
	 * The views for one conversation, in tab order.
	 *
	 * @param loaded  the skills loaded in this conversation; may be empty
	 * @param library shows a skill or directory in the library (the Skills
	 *                screen) by its path — where a reveal's link goes
	 */
	public static List<Tab> tabs(AgentContext.Report report, List<SessionHistory.RawTurn> turns,
			List<SkillIndex.Skill> loaded, Consumer<String> library) {
		return new ContextInspector(report, turns, loaded, library).tabs;
	}

	private final List<Tab> tabs = new ArrayList<>();
	private final Readout context = new Readout();
	private final Readout cycle = new Readout();
	private final Readout tools = new Readout();
	private final Readout skills = new Readout();
	private final Readout loads = new Readout();
	private final Consumer<String> library;

	/** What a cross-reference can reach: the anchors the other views carry. */
	private final Set<String> skillPaths = new HashSet<>();
	private final Map<String, String> toolByOperation = new HashMap<>();
	private final Set<String> loadRefs = new HashSet<>();

	private ContextInspector(AgentContext.Report report, List<SessionHistory.RawTurn> turns,
			List<SkillIndex.Skill> loaded, Consumer<String> library) {
		this.library = library;
		for (SkillIndex.Skill s : loaded) skillPaths.add(s.path());
		for (AgentContext.Tool t : report.tools()) {
			if (t.operation() != null && t.name() != null) toolByOperation.putIfAbsent(t.operation(), t.name());
		}
		for (AgentContext.Load l : report.loads()) {
			if (l.ref() != null) loadRefs.add(l.ref());
		}
		tabs.add(new Tab("Overview", Scrolls.vertical(overview(report))));
		tabs.add(new Tab("Messages (" + report.messages().size() + ")", Scrolls.vertical(messages(report))));
		tabs.add(new Tab("Cycle detail (" + turns.size() + ")", Scrolls.vertical(cycle(turns))));
		tabs.add(new Tab("Tools (" + report.tools().size() + ")", Scrolls.vertical(tools(report))));
		tabs.add(new Tab("Skills (" + loaded.size() + ")", Scrolls.vertical(skills(loaded, report))));
		tabs.add(new Tab("Loaded (" + report.loads().size() + ")", Scrolls.vertical(loads(report))));
		tabs.add(new Tab("Raw", raw(report.rawJson())));
	}

	// ------------------------------------------------------------------
	// Navigation: a link selects the tab holding another view and brings its detail into view
	// ------------------------------------------------------------------

	/**
	 * A link reading {@code text} that selects the tab holding {@code pane} —
	 * whichever tabbed panes the views were placed in, however deeply nested
	 * and however the pane is wrapped, each level in turn — and scrolls to
	 * the block anchored {@code id} (null: the top).
	 */
	private Readout.Link to(Readout pane, String id, String text) {
		return new Readout.Link(text, () -> {
			Component inner = pane;
			JTabbedPane tabbed;
			while ((tabbed = (JTabbedPane) SwingUtilities.getAncestorOfClass(JTabbedPane.class, inner)) != null) {
				Component tab = inner;
				while (tab.getParent() != null && tab.getParent() != tabbed) tab = tab.getParent();
				tabbed.setSelectedComponent(tab);
				inner = tabbed;
			}
			if (id != null) SwingUtilities.invokeLater(() -> pane.scrollTo(id));
		});
	}

	private static String skillAnchor(String path) {
		return "skill " + path;
	}

	private static String toolAnchor(String name) {
		return "tool " + name;
	}

	private static String messageAnchor(int index) {
		return "message " + index;
	}

	private static String loadAnchor(String ref) {
		return "load " + ref;
	}

	/** {@code path} as a link: to its entry here when the skill is loaded, else to it in the library. */
	private Object skillRef(String path) {
		if (skillPaths.contains(path)) return to(skills, skillAnchor(path), path);
		return new Readout.Link(path, () -> library.accept(path));
	}

	/** {@code operation} as a link to the tool the model has for it, else as text. */
	private Object toolRef(String operation) {
		String name = toolByOperation.get(operation);
		return (name != null) ? to(tools, toolAnchor(name), operation) : operation;
	}

	// ------------------------------------------------------------------
	// Overview
	// ------------------------------------------------------------------

	/**
	 * The conversation at a glance: a card with the model, its tokens so far
	 * and the budget as a meter; under it a tile for each piece worth opening
	 * (messages, tools, loaded entries), each pressing through to its tab.
	 */
	private JComponent overview(AgentContext.Report r) {
		JPanel p = new JPanel(new MigLayout("insets 20 24 20 24, fillx, wrap 3, gap 12 12",
			"[grow,fill,sg tile][grow,fill,sg tile][grow,fill,sg tile]", ""));
		p.setOpaque(false);

		Card card = new Card();
		card.setLayout(new MigLayout("insets 16 18 16 18, fillx, wrap 2, gapy 2", "[][grow,fill]", ""));
		card.add(new Badge("sparkles", Tone.ACCENT, 30), "spany 2, aligny center, gapright 10");
		JLabel model = Labels.lead(r.model().isBlank() ? "—" : r.model());
		model.setToolTipText(TIP_MODEL);
		card.add(model, "wmin 0");
		JLabel tokens = Labels.small((r.sessionTokens() != null) ? "Session tokens: " + r.sessionTokens()
			: "No tokens counted yet");
		tokens.setToolTipText(TIP_TOKENS);
		card.add(tokens, "wmin 0");

		int pct = r.budgetPercent();
		boolean over = pct >= 100;
		JLabel budget = Labels.small("Context budget");
		budget.setToolTipText(TIP_BUDGET);
		JLabel share = over ? Styles.classes(Labels.text(pct + "%"), Styles.STRONG, Styles.WARNING)
			: Styles.classes(Labels.text(pct + "%"), Styles.STRONG);
		JPanel budgetLine = new JPanel(new MigLayout("insets 0, fillx", "[grow][]", ""));
		budgetLine.setOpaque(false);
		budgetLine.add(budget);
		budgetLine.add(share);
		card.add(budgetLine, "span 2, growx, gaptop 14");
		JProgressBar meter = new JProgressBar(0, 100);
		meter.setValue(Math.min(pct, 100));
		meter.putClientProperty(FlatClientProperties.PROGRESS_BAR_LARGE_HEIGHT, true);
		if (over) Styles.style(meter, "foreground: $Brightside.warning");
		meter.setToolTipText(TIP_BUDGET);
		card.add(meter, "span 2, growx, gaptop 4");
		card.add(Labels.small(String.format("%,d of %,d bytes", r.budgetUsed(), r.budgetBytes())), "span 2, gaptop 4");
		p.add(card, "span 3, growx, wmin 0");

		p.add(new StatTile("messages-square", Tone.ACCENT, "Messages", Integer.toString(r.messages().size()),
			bands(r)).onPress(TIP_MESSAGES, open(context)), "wmin 0");
		boolean unavailable = !r.unavailable().isEmpty();
		p.add(new StatTile("wrench", unavailable ? Tone.WARNING : Tone.ACCENT, "Tools", Integer.toString(r.tools().size()),
			unavailable ? r.unavailable().size() + " unavailable" : null).onPress(TIP_TOOLS, open(tools)), "wmin 0");
		long truncated = r.loads().stream().filter(AgentContext.Load::truncated).count();
		p.add(new StatTile("layers", Tone.ACCENT, "Loaded", Integer.toString(r.loads().size()),
			truncated > 0 ? truncated + " truncated" : null).onPress(TIP_LOADED, open(loads)), "wmin 0");

		JLabel about = Labels.html("Exactly what the assistant's model receives for this conversation, assembled as "
			+ "for a live reply but not sent. The budget is the model's declared context size — a guide the "
			+ "assembler warns against, not a cap.", 440, SwingConstants.LEFT);
		Styles.classes(about, Styles.SMALL, Styles.MUTED);
		p.add(about, "span 3, gaptop 8, wmin 0");
		return p;
	}

	/** The messages by band — "head 2 · conversation 120 · tail 6" — when there is more than one. */
	private static String bands(AgentContext.Report r) {
		if (r.bands().size() < 2) return null;
		StringBuilder sb = new StringBuilder();
		for (AgentContext.Band b : r.bands()) {
			if (sb.length() > 0) sb.append(" · ");
			sb.append(b.name().toLowerCase()).append(' ').append(b.size());
		}
		return sb.toString();
	}

	/** Opens the tab holding {@code pane}, at its top. */
	private Runnable open(Readout pane) {
		return to(pane, null, "").action();
	}

	// ------------------------------------------------------------------
	// Context: every message the model sees, by band
	// ------------------------------------------------------------------

	private Readout messages(AgentContext.Report r) {
		Readout d = context;
		if (r.messages().isEmpty()) return d.note("No messages.");
		// A call and its result pair by id: each links to the other.
		Map<String, Integer> resultOf = new HashMap<>();
		Map<String, Integer> callOf = new HashMap<>();
		for (int i = 0; i < r.messages().size(); i++) {
			AgentContext.Message m = r.messages().get(i);
			if ("tool".equals(m.role()) && m.id() != null) resultOf.put(m.id(), i);
			for (AgentContext.Call c : m.calls()) {
				if (c.id() != null) callOf.putIfAbsent(c.id(), i);
			}
		}
		for (AgentContext.Band band : r.bands()) {
			d.section(band.name() + "  ·  " + span(band)).tip(bandTip(band.name()));
			for (int i = band.from(); i < band.to(); i++) {
				AgentContext.Message m = r.messages().get(i);
				List<Object> meta = new ArrayList<>();
				if (m.name() != null) {
					String text = m.name() + (m.id() != null ? "  (" + shortId(m.id()) + ")" : "");
					Integer call = (m.id() != null) ? callOf.get(m.id()) : null;
					meta.add((call != null) ? to(d, messageAnchor(call), text) : text);
				}
				for (AgentContext.Call c : m.calls()) {
					String text = "→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : "");
					Integer result = (c.id() != null) ? resultOf.get(c.id()) : null;
					meta.add((result != null) ? to(d, messageAnchor(result), text) : text);
				}
				meta.add(new Readout.Tip("#" + i, "Its place in the messages the model receives."));
				d.anchor(messageAnchor(i));
				d.entry(m.role(), m.error() ? Styles.ERROR : null, meta.toArray()).tip(roleTip(m.role()));

				boolean empty = true;
				if (!m.text().isBlank()) {
					d.excerpt(m.text(), false);
					empty = false;
				}
				for (AgentContext.Call c : m.calls()) {
					if (c.args() != null && !c.args().isBlank()) {
						d.caption("arguments  ·  " + c.name());
						d.excerpt(c.args(), true);
						empty = false;
					}
				}
				if (m.result() != null) {
					d.caption(m.error() ? "error result" : "result");
					d.excerpt(m.result(), true);
					empty = false;
				}
				if (empty) d.caption("(empty)");
			}
			if (r.cacheMarks().contains((long) band.to())) {
				d.note("A cached prefix ends here: the messages above are stable across inferences.");
			}
		}
		return d;
	}

	private static String span(AgentContext.Band b) {
		return (b.size() == 1) ? "message " + b.from()
			: "messages " + b.from() + "–" + (b.to() - 1);
	}

	/** What a band of the assembled messages is, by the name {@link AgentContext#bands} gives it. */
	private static String bandTip(String band) {
		return switch (band) {
			case "Head" -> "The fixed opening: the assistant's identity and instructions, the skills index and "
				+ "pinned context. Stable across inferences, so the provider can cache it.";
			case "Live" -> "Loaded context: skill instructions and context entries, as resolved for this conversation.";
			case "Conversation" -> "The turns so far, with a compacted stretch standing as its summary.";
			case "Tool loop" -> "The turn in progress: its tool calls and their results so far.";
			case "Tail" -> "What the assembler adds last: the task in hand and its notices, such as the "
				+ "context-budget warning.";
			default -> "The messages the model receives, in order.";
		};
	}

	/** What a message's or turn's role means. */
	private static String roleTip(String role) {
		if (role == null) return null;
		return switch (role) {
			case "system" -> "Instructions to the model, sent as the system message.";
			case "user" -> "From the owner — or a context entry presented in the owner's voice.";
			case "assistant" -> "The model's own words, or a compacted stretch's summary in its voice.";
			case "tool" -> "A tool's result, answering the call it names.";
			case "compacted" -> "A compacted stretch: its summary stands for the turns archived beneath it.";
			default -> null;
		};
	}

	// ------------------------------------------------------------------
	// Cycle detail: the stored turns
	// ------------------------------------------------------------------

	private Readout cycle(List<SessionHistory.RawTurn> turns) {
		Readout d = cycle;
		if (turns.isEmpty()) return d.note("No turns recorded for this conversation.");
		int i = 0;
		for (SessionHistory.RawTurn t : turns) {
			List<Object> meta = new ArrayList<>();
			if (t.meta() != null && !t.meta().isBlank()) meta.add(new Readout.Tip(t.meta(), TIP_META_CYCLE));
			for (SessionHistory.RawCall c : t.calls()) meta.add("→ " + c.name() + (c.id() != null ? "  (" + shortId(c.id()) + ")" : ""));
			meta.add(new Readout.Tip("#" + i++, "Its place among the stored turns."));
			d.entry(t.role(), t.error() ? Styles.ERROR : null, meta.toArray()).tip(roleTip(t.role()));

			boolean empty = true;
			if (t.content() != null && !t.content().isBlank()) {
				d.excerpt(t.content(), false);
				empty = false;
			}
			for (SessionHistory.RawCall c : t.calls()) {
				if (c.args() != null && !c.args().isBlank()) {
					d.caption("arguments  ·  " + c.name());
					d.excerpt(c.args(), true);
					empty = false;
				}
			}
			if (t.toolResult() != null && !t.toolResult().isBlank()) {
				d.caption(t.error() ? "error result" : "result");
				d.excerpt(t.toolResult(), true);
				empty = false;
			}
			if (empty) d.caption("(empty)");
		}
		return d;
	}

	// ------------------------------------------------------------------
	// Tools: every definition the model receives, by where it comes from
	// ------------------------------------------------------------------

	/**
	 * Every tool definition the model receives, grouped by where it comes from
	 * (a skill's tools appear only once the skill is loaded); then any
	 * configured tool that did not resolve.
	 */
	private Readout tools(AgentContext.Report r) {
		Readout d = tools;
		String source = null;
		for (AgentContext.Tool t : r.tools()) {
			String s = (t.source() != null) ? t.source() : "other";
			if (!s.equals(source)) {
				source = s;
				long n = r.tools().stream().filter(u -> s.equals((u.source() != null) ? u.source() : "other")).count();
				d.section(sourceTitle(s) + "  ·  " + n).tip(sourceTip(s));
			}
			// The section already names the source; the operation and declaring skill are what's new here.
			if (t.name() != null) d.anchor(toolAnchor(t.name()));
			d.entry((t.name() != null) ? t.name() : "(tool)", null, t.operation(),
				(t.skill() != null) ? skillRef(t.skill()) : null);
			if (t.description() == null || t.description().isBlank()) d.caption("(no description)");
			else d.excerpt(t.description(), false);
		}
		if (!r.unavailable().isEmpty()) {
			d.section("Unavailable  ·  " + r.unavailable().size()).tip(TIP_UNAVAILABLE);
			for (String u : r.unavailable()) d.note(u);
		}
		if (r.tools().isEmpty() && r.unavailable().isEmpty()) d.note("No tools offered.");
		return d;
	}

	private static String sourceTitle(String source) {
		return switch (source) {
			case "harness" -> "Harness";
			case "default" -> "Default";
			case "config" -> "Configured";
			case "skill" -> "From loaded skills";
			default -> source;
		};
	}

	/** Where a tool definition comes from. */
	private static String sourceTip(String source) {
		return switch (source) {
			case "harness" -> "Controls the venue's harness offers an agent that names them — compacting the "
				+ "conversation, loading a skill.";
			case "default" -> "Read-only reads of the agent's own workspace: the default tools its configuration keeps on.";
			case "config" -> "Tools named in the agent's configuration, always on.";
			case "skill" -> "Tools granted by skills loaded in this conversation. A skill's tools arrive only once it is loaded.";
			default -> "Tools from " + source + ".";
		};
	}

	// ------------------------------------------------------------------
	// Skills: the ones loaded in this conversation
	// ------------------------------------------------------------------

	private Readout skills(List<SkillIndex.Skill> loaded, AgentContext.Report r) {
		Readout d = skills;
		if (loaded.isEmpty()) return d.note("No skill is loaded in this conversation.");
		Map<String, AgentContext.Load> entries = new HashMap<>();
		for (AgentContext.Load l : r.loads()) {
			if (l.ref() != null) entries.put(l.ref(), l);
		}
		d.note("The skills loaded in this conversation: their instructions are in the context and their "
			+ "tools in the palette. What each reveals is in the library, the Skills screen, which a link "
			+ "below opens at that skill or directory.");
		for (SkillIndex.Skill s : loaded) {
			List<Object> meta = new ArrayList<>();
			AgentContext.Load l = entries.get(s.path());
			if (l != null) {
				String text = "loaded" + (l.status() != null ? "  ·  " + l.status() : "");
				meta.add(loadRefs.contains(s.path()) ? to(loads, loadAnchor(s.path()), text) : text);
			}
			if (!s.tools().isEmpty()) {
				meta.add(new Readout.Tip(s.tools().size() + (s.tools().size() == 1 ? " tool" : " tools"), TIP_GRANTS));
			}
			if (!s.children().isEmpty()) meta.add(new Readout.Tip("reveals " + s.children().size() + " more", TIP_REVEALS));
			meta.add(new Readout.Tip(s.path(), TIP_PATH));
			d.anchor(skillAnchor(s.path()));
			d.entry(s.name(), null, meta.toArray());

			d.excerpt((s.description() != null) ? s.description() : "(no description)", false);
			if (!s.tools().isEmpty()) {
				d.caption("tools it grants");
				d.lines(true, s.tools().stream().map(this::toolRef).toList());
			}
			if (!s.children().isEmpty()) {
				d.caption("what it reveals  ·  in the library");
				d.lines(true, s.children().stream().map(this::skillRef).toList());
			}
		}
		return d;
	}

	// ------------------------------------------------------------------
	// Loaded entries
	// ------------------------------------------------------------------

	private Readout loads(AgentContext.Report r) {
		Readout d = loads;
		if (r.loads().isEmpty()) return d.note("Nothing loaded.");
		for (AgentContext.Load l : r.loads()) {
			if (l.ref() != null) d.anchor(loadAnchor(l.ref()));
			d.entry((l.ref() != null) ? l.ref() : "(entry)", null, tipped(l.kind(), kindTip(l.kind())),
				tipped(l.status(), statusTip(l.status())),
				(l.ref() != null && skillPaths.contains(l.ref())) ? to(skills, skillAnchor(l.ref()), "the skill") : null);
			d.prose(accounting(l)).tip(TIP_ACCOUNTING);
		}
		return d;
	}

	/**
	 * A load's size against its budget. An entry the session's cached prefix
	 * already holds is not measured again on a later assembly (the venue
	 * reports it materialised with no bytes), so it is described rather than
	 * shown as nothing; a tools-only entry has no text to measure.
	 */
	static String accounting(AgentContext.Load l) {
		StringBuilder sb = new StringBuilder();
		if ("tools".equals(l.kind())) {
			sb.append("no text, tools only");
			if (l.budget() > 0) sb.append(String.format("  ·  %,d-byte budget", l.budget()));
		} else if (l.bytes() == 0 && "materialised".equals(l.status())) {
			sb.append("in the cached prefix, not re-measured");
			if (l.budget() > 0) sb.append(String.format("  ·  %,d-byte budget", l.budget()));
		} else {
			sb.append(String.format("%,d bytes", l.bytes()));
			if (l.budget() > 0) sb.append(String.format("  of a %,d-byte budget", l.budget()));
		}
		if (l.truncated()) sb.append("  ·  truncated");
		if (l.deduplicated()) sb.append("  ·  deduplicated");
		return sb.toString();
	}

	/** {@code text} explained by {@code tip} — or plain when there is no explanation, or nothing to explain. */
	private static Object tipped(String text, String tip) {
		return (text != null && tip != null) ? new Readout.Tip(text, tip) : text;
	}

	/** What a load entry's kind means. */
	private static String kindTip(String kind) {
		if (kind == null) return null;
		return switch (kind) {
			case "skill" -> "A skill: instructions placed in the context, with the tools it grants.";
			case "load" -> "A load: an operation's result or a lattice value, placed in the context under its label.";
			case "tools" -> "Tools only: an entry that grants tools and adds no text.";
			default -> null;
		};
	}

	/** What a load entry's status means. */
	private static String statusTip(String status) {
		if (status == null) return null;
		return switch (status) {
			case "materialised" -> "Rendered into the session's cached prefix, where it stays as it is between "
				+ "inferences until the prefix is next rebuilt.";
			case "appended" -> "Loaded by the assistant during the conversation: its text was appended to the "
				+ "history rather than rendered into the cached prefix.";
			case "resolved" -> "Read afresh for this assembly.";
			case "deduplicated" -> "The same skill is already loaded under another entry, so this one adds nothing.";
			case "absent" -> "Nothing was found at its path, or its operation returned nothing.";
			case "unavailable" -> "Its path or operation could not be read.";
			default -> null;
		};
	}

	private static String shortId(String id) {
		return (id.length() > 12) ? id.substring(0, 12) + "…" : id;
	}
}
