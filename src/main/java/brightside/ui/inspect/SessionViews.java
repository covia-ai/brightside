package brightside.ui.inspect;

import java.awt.BorderLayout;
import java.awt.Component;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.AgentContext;
import brightside.AgentInfo;
import brightside.SessionHistory;
import brightside.SkillIndex;
import brightside.ui.chat.ChatPanel;
import brightside.ui.components.Readout;
import brightside.ui.components.Scrolls;

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
public final class SessionViews extends JPanel {

	/** Reads a view's data off the event thread; the answer arrives through {@code show…}. */
	public interface Host {
		/** Read what the model receives for the conversation, answering through {@link #showSession}. */
		void loadSession(String sessionId);

		/** Read what the agent is, answering through {@link #showAgent}. */
		void loadAgent(String agentId);

		/** Show a skill or directory in the library — the Skills screen — by its path. */
		void showSkill(String path);
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

	static final String NO_CONVERSATION = "No conversation on screen. Choose one from the list, or send a message.";
	static final String NO_AGENT = "No agent on screen.";
	static final String READING = "Reading…";

	private final Host host;
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
		noteSession(NO_CONVERSATION);
		noteAgent(NO_AGENT);
		tabs.addChangeListener(e -> onSelected());
		add(tabs, BorderLayout.CENTER);
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
		noteSession((sessionId == null) ? NO_CONVERSATION : READING);
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
		noteAgent((agentId == null) ? NO_AGENT : READING);
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
		host.loadSession(sessionId);
	}

	private void readAgent() {
		if (agentId == null || agentShown == agentVersion || agentAsked == agentVersion) return;
		agentAsked = agentVersion;
		host.loadAgent(agentId);
	}

	// ------------------------------------------------------------------
	// Answers
	// ------------------------------------------------------------------

	/**
	 * The conversation's context as read; {@code report} null when it could
	 * not be read. Ignored unless {@code sessionId} is still the one on screen.
	 */
	public void showSession(String sessionId, AgentContext.Report report, List<SessionHistory.RawTurn> turns,
			List<SkillIndex.Skill> skills) {
		if (!Objects.equals(sessionId, this.sessionId)) return;
		sessionShown = sessionAsked;
		if (report == null) {
			noteSession("Brightside couldn't read the context for this conversation.");
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
	public void showAgent(String agentId, AgentInfo.Summary info) {
		if (!Objects.equals(agentId, this.agentId)) return;
		agentShown = agentAsked;
		if (info == null) noteAgent("Brightside couldn't read that agent.");
		else fill(slots.get(View.AGENT), AgentInspector.view(info));
		if (current() == View.AGENT) readAgent();
	}

	private void noteSession(String text) {
		fill(slots.get(View.OVERVIEW), Scrolls.vertical(new Readout().note(text)));
		for (Part p : Part.values()) fillPart(p, p.title, Scrolls.vertical(new Readout().note(text)));
	}

	private void noteAgent(String text) {
		fill(slots.get(View.AGENT), Scrolls.vertical(new Readout().note(text)));
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
