package brightside.ui.inspect;

import static brightside.BrightSide.APP_NAME;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.AgentContext;
import brightside.AgentInfo;
import brightside.SessionHistory;
import brightside.SkillIndex;
import brightside.EmbeddedVenue;
import brightside.chat.ChatSession;
import brightside.ui.chat.ActivityLabels;
import brightside.ui.chat.ChatPanel;
import brightside.ui.chat.SessionFeed;
import brightside.ui.components.Clipboard;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Labels;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Toast;
import convex.core.data.Maps;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Strings;
import convex.core.lang.RT;
import covia.grid.Venue;
import brightside.ui.components.EmptyState;
import brightside.ui.components.Loading;
import brightside.ui.components.Panels;
import brightside.ui.components.Tone;

/**
 * The chat with tabs along its top: <em>Chat</em>, then the read-only views
 * of the conversation on screen — <em>Overview</em> and <em>Context</em>,
 * what its model receives, from {@link ContextInspector} — and <em>Agent</em>,
 * what it belongs to, from {@link AgentInspector}. Context carries the pieces
 * of the assembled context as its own row of tabs ({@link Part}), so the top
 * row stays four wide. On the Sessions screen the tab row shows; on Home the
 * chat stands alone and the row is hidden, so the everyday screen carries
 * none of it.
 *
 * <p>The views are read when one is first shown, and read again when the
 * conversation on screen moves on or changes, so a view is never older than
 * the transcript beside it. Reads run off the event thread through the
 * {@link Host} and answer through {@link #showSession} and {@link #showAgent};
 * an answer for a conversation or agent no longer on screen is dropped.
 */
@SuppressWarnings("serial")
public final class SessionViews extends JPanel implements AutoCloseable {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SessionViews.class);

	/** Reads a view's data off the event thread; the answer arrives through {@code show…}. */
	public interface Host {
		/** Read what the model receives for the conversation, answering through {@link #showSession}. */
		void loadSession(String sessionId, int version);

		/** Read what the agent is, answering through {@link #showAgent}. */
		void loadAgent(String agentId, int version);

		/** Give the agent new instructions, from its Agent tab. */
		void saveInstructions(String agentId, String instructions);

		/** Show a skill or directory in the library — the Skills screen — by its path. */
		void showSkill(String path);

		void sessionsChanged(List<SessionHistory.Session> sessions, String selectedId);

		void agentsChanged();

		/** Open the record of what was done (Jobs, until the Record view lands). */
		default void openRecord() {
		}
	}

	/** The top row, in order: the chat, the conversation's overview and context, its agent. */
	public enum View {
		CHAT("Chat"), OVERVIEW("Overview"), CONTEXT("Context"), AGENT("Agent"), RECORD("Record");

		final String title;

		View(String title) {
			this.title = title;
		}

		boolean perSession() {
			return this == OVERVIEW || this == CONTEXT;
		}
	}

	/** The Context tab's own row, in order: the pieces of what the model receives. */
	public enum Part {
		MESSAGES("Messages"), CYCLE("Cycle detail"), TOOLS("Tools"), SKILLS("Skills"), LOADED("Loaded"), RAW("Raw");

		final String title;

		Part(String title) {
			this.title = title;
		}
	}

	// What a view shows while it has no reading of its own: made afresh for each slot it fills.
	private static JComponent noConversation() {
		return new EmptyState("messages-square", Tone.MUTED, "No conversation on screen",
			"Choose one from the list, or send a message.").bare();
	}

	private static JComponent noAgent() {
		return new EmptyState("user-round", Tone.MUTED, "No agent on screen", null).bare();
	}

	private static JComponent reading() {
		return Panels.centred(new Loading("Reading…"));
	}

	private final Host host;
	private final ChatPanel chat;
	private ChatSession session;
	private Venue client;
	private Supplier<ACell> agentRecord;
	private SessionFeed feed;
	private AutoCloseable subscription;
	private List<SessionHistory.Session> sessions = List.of();
	private boolean restore;
	private final JTabbedPane tabs = new JTabbedPane();
	/** Inside the Context tab: one tab per piece. */
	private final JTabbedPane parts = new JTabbedPane();
	/** The inspector docked beside the chat on wide windows: the same views, in a column of their own. */
	private final JTabbedPane side = new JTabbedPane();
	private final JPanel sideWrap = new JPanel(new BorderLayout());
	private boolean docked;
	/** Each top-row view's tab content: a slot whose document is replaced on every read (Context's holds {@link #parts}). */
	private final Map<View, JPanel> slots = new EnumMap<>(View.class);
	/** Each piece's tab content, likewise. */
	private final Map<Part, JPanel> partSlots = new EnumMap<>(Part.class);
	private boolean tabsVisible;
	/** The tab last chosen on Sessions, restored on return. */
	private View chosen = View.CHAT;

	private String sessionId;
	private String agentId;
	// A view is current when the read it shows is for the latest version of
	// what it describes; each change bumps the version, and an answer clears
	// the version it was asked for.
	private int sessionVersion, sessionAsked = -1, sessionShown = -1;
	private int agentVersion, agentAsked = -1, agentShown = -1;

	public SessionViews(ChatPanel chat, Host host) {
		super(new BorderLayout());
		this.host = host;
		this.chat = chat;
		chat.setConversationCommittedListener(this::committed);
		setOpaque(false);
		tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
		tabs.putClientProperty(FlatClientProperties.TABBED_PANE_HIDE_TAB_AREA_WITH_ONE_TAB, true);
		tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE, FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
		tabs.addTab(View.CHAT.title, chat);
		for (View v : View.values()) {
			if (v == View.CHAT) continue;
			slots.put(v, slot());
		}
		// The pieces: a second, card-style row so it reads as the Context tab's own.
		parts.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
		parts.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE, FlatClientProperties.TABBED_PANE_TAB_TYPE_CARD);
		for (Part p : Part.values()) {
			JPanel slot = slot();
			partSlots.put(p, slot);
			parts.addTab(p.title, slot);
		}
		slots.get(View.CONTEXT).add(parts, BorderLayout.CENTER);
		slots.get(View.RECORD).add(recordNote(), BorderLayout.CENTER);
		noteSession(SessionViews::noConversation);
		noteAgent(SessionViews::noAgent);
		tabs.addChangeListener(e -> onSelected());
		side.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
		side.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_TYPE, FlatClientProperties.TABBED_PANE_TAB_TYPE_UNDERLINED);
		side.addChangeListener(e -> onSelected());
		sideWrap.add(side, BorderLayout.CENTER);
		sideWrap.setBorder(brightside.ui.components.Borders.hairline(false, true, false, false));
		sideWrap.setPreferredSize(new java.awt.Dimension(brightside.ui.shell.ShellLayout.SIDE, 0));
		add(tabs, BorderLayout.CENTER);
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refresh();
		});
	}

	/**
	 * The chat's Record tab: what this chat's agents did, as recorded. Jobs are
	 * not yet tied to the chat that started them, so it says where the record
	 * is today and opens it; this chat's own slice comes with the Record view.
	 */
	private JPanel recordNote() {
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		// Wraps to any width, the docked inspector's 320 pt included.
		JPanel column = new JPanel(new net.miginfocom.swing.MigLayout("insets 24 16 16 16, fillx, wrap 1", "[grow,fill]", ""));
		column.setOpaque(false);
		column.add(brightside.ui.components.Labels.icon(brightside.ui.components.Lucide.icon("scroll-text", 28,
			brightside.ui.components.Theme::muted)), "align center");
		brightside.ui.components.SelectableText title = new brightside.ui.components.SelectableText(
			"This chat's record is coming").bold();
		column.add(title, "growx, wmin 0, w 10::");
		column.add(brightside.ui.components.SelectableText.description(
			"Every job your agents run is recorded in Jobs, in order, with who ran it and when.").small(),
			"growx, wmin 0, w 10::");
		column.add(brightside.ui.components.Buttons.link("Open Jobs", () -> host.openRecord()), "gaptop 8");
		p.add(column, BorderLayout.NORTH);
		return p;
	}

	/** Bind the live transcript and its subscription to this view's current agent. Event thread. */
	public void bind(EmbeddedVenue venue, String owner, ChatSession session,
			List<SessionHistory.Session> sessions) {
		close();
		this.session = session;
		this.client = venue.clientAs(owner);
		this.sessions = sessions;
		setSession(session.sessionId());
		host.sessionsChanged(sessions, sessionId);
		String aid = session.config().agentId();
		setAgent(aid);
		agentRecord = () -> venue.agentRecord(owner, aid);
		feed = new SessionFeed(agentRecord, this::isShowing, this::updated);
		SessionFeed bound = feed;
		ActivityLabels labels = new ActivityLabels();
		subscription = venue.subscribeAgent(owner, aid, event -> {
			String label = labels.label(venue, owner, event);
			SwingUtilities.invokeLater(() -> {
				if (feed != bound) return;
				followAcceptedSession();
				bound.refresh();
				if (sessionId == null || event.concerns(Blob.parse(sessionId))) chat.showActivity(event, label);
			});
		});
		feed.select(sessionId);
	}

	private void updated(SessionFeed.Update update) {
		if (followAcceptedSession()) return;
		sessions = update.sessions();
		if (update.selected() != null) {
			if (restore) chat.restoreSelected(update.selected().items());
			else chat.refreshTo(update.selected().items(), update.active());
			restore = false;
			if (!update.active()) sessionChanged();
		}
		host.sessionsChanged(sessions, sessionId);
		host.agentsChanged();
	}

	/** The venue can assign the first session before the send's completion arrives. */
	private boolean followAcceptedSession() {
		if (session == null || Objects.equals(sessionId, session.sessionId())) return false;
		setSession(session.sessionId());
		feed.select(sessionId);
		return true;
	}

	private void committed(String id) {
		if (session == null || !Objects.equals(id, session.sessionId())) return;
		setSession(id);
		// A prior read may have seen the final state while the composer was busy.
		feed.select(id);
	}

	public void refresh() {
		if (feed != null) feed.refresh();
	}

	/** Select immediately, invalidating the previous transcript's pending work. */
	public void openSession(String id) {
		if (session == null || id == null || Objects.equals(id, sessionId)) return;
		session.resume(id);
		chat.startNewConversation();
		restore = true;
		setSession(id);
		host.sessionsChanged(sessions, id);
		feed.select(id);
	}

	public void newConversation() {
		if (session == null) return;
		session.reset();
		chat.startNewConversation();
		restore = false;
		setSession(null);
		host.sessionsChanged(sessions, null);
		feed.select(null);
	}

	/** Session actions use the same principal and agent as the view that requested them. */
	public void renameSession(String id, String title) {
		if (session == null) return;
		var input = Maps.of("agentId", session.config().agentId(), "sessionId", id);
		if (title != null && !title.isBlank()) input = input.assoc(Strings.create("title"), Strings.create(title.trim()));
		run("v/ops/agent/rename-session", input, this::refresh);
	}

	public void deleteSession(String id) {
		if (session == null) return;
		run("v/ops/agent/delete-session", Maps.of("agentId", session.config().agentId(), "sessionId", id), () -> {
			if (Objects.equals(id, sessionId)) newConversation();
			refresh();
		});
	}

	/**
	 * Shows the assistant's saved summary of a conversation, through Covia's
	 * {@code agent:summarise}: written the first time, written again only once
	 * the conversation has new turns, and never added to the conversation.
	 */
	public void summariseSession(String id) {
		summarise(id, summary -> Dialogs.info(this, "Summary", scrolled(new SelectableText(summary).wrapAt(520))));
	}

	/**
	 * Compacts a conversation under a summary; every message stays beneath it.
	 * The open conversation asks the assistant, which writes the summary with
	 * its own compact control. Another is summarised first; the owner reads
	 * the summary, and may edit it, before the venue compacts that idle
	 * conversation under it ({@code agent:compact-session}).
	 */
	public void compactSession(String id) {
		if (session == null) return;
		if (Objects.equals(id, sessionId)) {
			chat.confirmCompact();
			return;
		}
		String agentId = session.config().agentId();
		summarise(id, summary -> {
			JTextArea text = new JTextArea(summary, 12, 48);
			text.setLineWrap(true);
			text.setWrapStyleWord(true);
			JPanel form = Panels.column();
			form.add(Labels.small("<html>" + APP_NAME + " will carry this conversation on from this summary, which you can edit."
				+ "<br>Every message is kept beneath it.</html>"));
			form.add(new JScrollPane(text));
			if (!Dialogs.form(this, "Compact this conversation?", form)) return;
			String chosen = text.getText().strip();
			if (chosen.isEmpty()) return;
			run("v/ops/agent/compact-session", Maps.of("agentId", agentId, "sessionId", id, "summary", chosen), this::refresh);
		});
	}

	/** Has the assistant summarise a conversation, then hands the summary to {@code show} on the event thread. */
	private void summarise(String id, java.util.function.Consumer<String> show) {
		SessionFeed bound = feed;
		Venue caller = client;
		if (bound == null || caller == null || session == null) return;
		ACell input = Maps.of("agentId", session.config().agentId(), "sessionId", id);
		Toast.show(this, APP_NAME + " is summarising the conversation…", "sparkles", Tone.MUTED);
		caller.run("v/ops/agent/summarise", input).orTimeout(5, TimeUnit.MINUTES).whenComplete((out, e) -> {
			String summary = (e == null) ? String.valueOf(RT.getIn(out, "response")) : null;
			SwingUtilities.invokeLater(() -> {
				if (feed != bound) return;
				if (summary == null || summary.isBlank() || "null".equals(summary)) {
					if (e != null) log.warn("Could not summarise the conversation", e);
					Dialogs.warn(this, "Summary", "Sorry, " + APP_NAME + " couldn't summarise that conversation.");
					return;
				}
				show.accept(summary);
			});
		});
	}

	private static JScrollPane scrolled(JComponent c) {
		JScrollPane pane = new JScrollPane(c);
		pane.setPreferredSize(new java.awt.Dimension(560, 320));
		return pane;
	}

	private void run(String operation, ACell input, Runnable completed) {
		SessionFeed bound = feed;
		Venue caller = client;
		if (bound == null || caller == null) return;
		CompletableFuture.runAsync(() -> {
			try {
				var job = caller.invoke(operation, input).get(30, TimeUnit.SECONDS);
				job.future().get(30, TimeUnit.SECONDS);
				SwingUtilities.invokeLater(() -> {
					if (feed == bound) completed.run();
				});
			} catch (Exception e) {
				log.warn("Could not run {}", operation, e);
				SwingUtilities.invokeLater(() -> {
					if (feed == bound) chat.appendSystem("Sorry, " + APP_NAME + " couldn't change that conversation.");
				});
			}
		});
	}

	public void copyTranscript(String id) {
		SessionFeed bound = feed;
		Supplier<ACell> read = agentRecord;
		if (bound == null) return;
		CompletableFuture.supplyAsync(() -> SessionHistory.snapshotOf(read.get(), id)).thenAccept(snapshot -> {
			SwingUtilities.invokeLater(() -> {
				if (feed != bound) return;
				if (snapshot != null && id.equals(snapshot.sessionId())) Clipboard.copy(SessionHistory.plainText(snapshot.items()));
				else chat.appendSystem("Sorry, " + APP_NAME + " couldn't read that conversation.");
			});
		});
	}

	/** Release this view's reads and live subscription on logout, rebind or exit. */
	@Override
	public void close() {
		if (feed != null) feed.close();
		feed = null;
		session = null;
		client = null;
		agentRecord = null;
		restore = false;
		if (subscription != null) {
			try {
				subscription.close();
			} catch (Exception ignored) {
				// Unsubscribing during teardown is best-effort.
			}
			subscription = null;
		}
		setSession(null);
		setAgent(null);
	}

	private static JPanel slot() {
		JPanel slot = new JPanel(new BorderLayout());
		slot.setOpaque(false);
		return slot;
	}

	/** Sessions shows the tab row; Home hides it and shows the chat alone. */
	public void setTabsVisible(boolean visible) {
		if (visible == tabsVisible) return;
		if (!visible) chosen = current();
		tabsVisible = visible;
		rebuild();
	}

	/**
	 * Docked: Overview, Context and Agent sit in an inspector column beside
	 * the chat (wide windows); otherwise they are tabs above it. Nothing is
	 * lost either way: the same views, in one place or the other.
	 */
	public void setInspectorDocked(boolean docked) {
		if (docked == this.docked) return;
		View keep = tabsVisible ? current() : chosen;
		this.docked = docked;
		rebuild();
		if (tabsVisible) select(keep);
	}

	public boolean inspectorDocked() {
		return docked && tabsVisible;
	}

	private void rebuild() {
		while (tabs.getTabCount() > 1) tabs.removeTabAt(1);
		side.removeAll();
		remove(sideWrap);
		if (tabsVisible) {
			for (View v : View.values()) {
				if (v == View.CHAT) continue;
				if (docked) side.addTab(v.title, slots.get(v));
				else tabs.addTab(v.title, slots.get(v));
			}
			if (docked) add(sideWrap, BorderLayout.EAST);
			select(chosen);
		}
		revalidate();
		repaint();
	}

	/** Bring a view to the front; on Home only the chat can be. */
	public void select(View v) {
		if (docked && tabsVisible && v != View.CHAT) {
			tabs.setSelectedIndex(0);
			side.setSelectedComponent(slots.get(v));
			return;
		}
		int i = (v == View.CHAT) ? 0 : tabs.indexOfComponent(slots.get(v));
		if (i >= 0) tabs.setSelectedIndex(i);
	}

	/** Bring a piece of the context to the front, with the Context tab; on Home only the chat can be. */
	public void select(Part p) {
		select(View.CONTEXT);
		parts.setSelectedComponent(partSlots.get(p));
	}

	private View current() {
		Component c = (docked && tabsVisible && side.getSelectedComponent() != null) ? side.getSelectedComponent()
			: tabs.getSelectedComponent();
		for (Map.Entry<View, JPanel> e : slots.entrySet()) {
			if (e.getValue() == c) return e.getKey();
		}
		return View.CHAT;
	}

	private void onSelected() {
		View v = current();
		if (tabsVisible) chosen = v;
		if (v == View.AGENT) readAgent();
		else if (v.perSession()) readSession();
	}

	// ------------------------------------------------------------------
	// What is on screen
	// ------------------------------------------------------------------

	/** The conversation on screen (null: none). Its views are read afresh when next shown. */
	public void setSession(String sessionId) {
		if (Objects.equals(sessionId, this.sessionId)) return;
		this.sessionId = sessionId;
		sessionVersion++;
		noteSession((sessionId == null) ? SessionViews::noConversation : SessionViews::reading);
		if (current().perSession()) readSession();
	}

	/** The conversation on screen moved on (a reply landed, a rename): read its views again if one is showing. */
	public void sessionChanged() {
		sessionVersion++;
		if (current().perSession()) readSession();
	}

	/** The agent on screen (null: none). Its view is read afresh when next shown. */
	public void setAgent(String agentId) {
		if (Objects.equals(agentId, this.agentId)) return;
		this.agentId = agentId;
		agentVersion++;
		noteAgent((agentId == null) ? SessionViews::noAgent : SessionViews::reading);
		if (current() == View.AGENT) readAgent();
	}

	/** The agents changed (a rename, a new one): read the agent's view again if it is showing. */
	public void agentChanged() {
		agentVersion++;
		if (current() == View.AGENT) readAgent();
	}

	private void readSession() {
		if (sessionId == null || sessionShown == sessionVersion || sessionAsked == sessionVersion) return;
		sessionAsked = sessionVersion;
		host.loadSession(sessionId, sessionAsked);
	}

	private void readAgent() {
		if (agentId == null || agentShown == agentVersion || agentAsked == agentVersion) return;
		agentAsked = agentVersion;
		host.loadAgent(agentId, agentAsked);
	}

	// ------------------------------------------------------------------
	// Answers
	// ------------------------------------------------------------------

	/**
	 * The conversation's context as read; {@code report} null when it could
	 * not be read. Ignored unless {@code sessionId} is still the one on screen.
	 */
	public void showSession(String sessionId, int version, AgentContext.Report report, List<SessionHistory.RawTurn> turns,
			List<SkillIndex.Skill> skills) {
		if (!Objects.equals(sessionId, this.sessionId) || version != sessionVersion) return;
		sessionShown = version;
		if (report == null) {
			noteSession(() -> unreadable("Couldn't read this conversation's context"));
		} else {
			// The inspector's tabs in order: the overview, then one per piece, back in the Context tab.
			List<ContextInspector.Tab> views = ContextInspector.tabs(report, turns, skills, host::showSkill);
			fill(slots.get(View.OVERVIEW), views.get(0).view());
			fill(slots.get(View.CONTEXT), parts);
			Part[] pieces = Part.values();
			for (int i = 0; i < pieces.length; i++) {
				ContextInspector.Tab t = views.get(i + 1);
				fillPart(pieces[i], t.title(), t.view());
			}
		}
		// Moved on while being read: read again.
		if (current().perSession()) readSession();
	}

	/**
	 * The agent as read; {@code info} null when it could not be read;
	 * {@code editable}: its instructions can be changed from the view. Ignored
	 * unless {@code agentId} is still on screen.
	 */
	public void showAgent(String agentId, int version, AgentInfo.Summary info, boolean editable) {
		if (!Objects.equals(agentId, this.agentId) || version != agentVersion) return;
		agentShown = version;
		if (info == null) noteAgent(() -> unreadable("Couldn't read this agent"));
		else fill(slots.get(View.AGENT), AgentInspector.view(info,
			editable ? text -> host.saveInstructions(agentId, text) : null));
		if (current() == View.AGENT) readAgent();
	}

	/**
	 * The per-conversation views show {@code state} in place of a reading: the
	 * Overview one, and the Context tab one in place of its row of pieces, whose
	 * previous documents are let go.
	 */
	private void noteSession(Supplier<JComponent> state) {
		fill(slots.get(View.OVERVIEW), state.get());
		fill(slots.get(View.CONTEXT), state.get());
		for (JPanel slot : partSlots.values()) slot.removeAll();
	}

	private void noteAgent(Supplier<JComponent> state) {
		fill(slots.get(View.AGENT), state.get());
	}

	private static JComponent unreadable(String title) {
		return new EmptyState("circle-alert", Tone.ERROR, title, null).bare();
	}

	/** Replace a piece's document and its tab's title (a count where one is telling). */
	private void fillPart(Part p, String title, JComponent content) {
		JPanel slot = partSlots.get(p);
		fill(slot, content);
		int i = parts.indexOfComponent(slot);
		if (i >= 0) parts.setTitleAt(i, title);
	}

	/** Replace a slot's document. */
	private static void fill(JPanel slot, JComponent content) {
		slot.removeAll();
		slot.add(content, BorderLayout.CENTER);
		slot.revalidate();
		slot.repaint();
	}
}
