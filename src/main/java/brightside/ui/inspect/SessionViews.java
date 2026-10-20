package brightside.ui.inspect;

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
import convex.core.data.Maps;
import convex.core.data.ACell;
import convex.core.data.Strings;
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

		/** Show a skill or directory in the library — the Skills screen — by its path. */
		void showSkill(String path);

		void sessionsChanged(List<SessionHistory.Session> sessions, String selectedId);

		void agentsChanged();
	}

	/** The top row, in order: the chat, the conversation's overview and context, its agent. */
	public enum View {
		CHAT("Chat"), OVERVIEW("Overview"), CONTEXT("Context"), AGENT("Agent");

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
		return new EmptyState("bot", Tone.MUTED, "No agent on screen", null).bare();
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
		noteSession(SessionViews::noConversation);
		noteAgent(SessionViews::noAgent);
		tabs.addChangeListener(e -> onSelected());
		add(tabs, BorderLayout.CENTER);
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refresh();
		});
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
				if (sessionId == null || event.concerns(Strings.create(sessionId))) chat.showActivity(event, label);
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
					if (feed == bound) chat.appendSystem("Sorry — Brightside couldn't change that conversation.");
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
				else chat.appendSystem("Sorry — Brightside couldn't read that conversation.");
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
		tabsVisible = visible;
		if (visible) {
			for (View v : View.values()) {
				if (v != View.CHAT) tabs.addTab(v.title, slots.get(v));
			}
			select(chosen);
		} else {
			chosen = current();
			while (tabs.getTabCount() > 1) tabs.removeTabAt(1);
		}
	}

	/** Bring a view to the front; on Home only the chat can be. */
	public void select(View v) {
		int i = (v == View.CHAT) ? 0 : tabs.indexOfComponent(slots.get(v));
		if (i >= 0) tabs.setSelectedIndex(i);
	}

	/** Bring a piece of the context to the front, with the Context tab; on Home only the chat can be. */
	public void select(Part p) {
		select(View.CONTEXT);
		parts.setSelectedComponent(partSlots.get(p));
	}

	private View current() {
		Component c = tabs.getSelectedComponent();
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
			// The inspector's tabs in order: the overview, then one per piece.
			List<ContextInspector.Tab> views = ContextInspector.tabs(report, turns, skills, host::showSkill);
			fill(slots.get(View.OVERVIEW), views.get(0).view());
			Part[] pieces = Part.values();
			for (int i = 0; i < pieces.length; i++) {
				ContextInspector.Tab t = views.get(i + 1);
				fillPart(pieces[i], t.title(), t.view());
			}
		}
		// Moved on while being read: read again.
		if (current().perSession()) readSession();
	}

	/** The agent as read; {@code info} null when it could not be read. Ignored unless {@code agentId} is still on screen. */
	public void showAgent(String agentId, int version, AgentInfo.Summary info) {
		if (!Objects.equals(agentId, this.agentId) || version != agentVersion) return;
		agentShown = version;
		if (info == null) noteAgent(() -> unreadable("Couldn't read this agent"));
		else fill(slots.get(View.AGENT), AgentInspector.view(info));
		if (current() == View.AGENT) readAgent();
	}

	/** Every per-conversation view shows {@code state} (a fresh one each) in place of a reading. */
	private void noteSession(Supplier<JComponent> state) {
		fill(slots.get(View.OVERVIEW), state.get());
		for (Part p : Part.values()) fillPart(p, p.title, state.get());
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
