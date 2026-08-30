package brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

import brightside.model.AgentRef;
import brightside.ui.Lucide;
import brightside.ui.PressButton;

/**
 * The agents pane (left of the sessions list on the Sessions screen): one row per
 * agent the user owns — e.g. Brightside and Bob — each with its own conversations.
 * Selecting one switches the chat (and the sessions list) to that agent; a
 * right-click offers its info screen and deletion. The separate button beneath
 * the scrollable list creates another agent.
 *
 * <p>Dumb like {@link ConversationList}: it renders what it's given and reports
 * clicks through a {@link Listener}; {@code BrightSide} owns switching/creating.
 */
@SuppressWarnings("serial")
public final class AgentList extends JPanel {

	/** Reports the user's intent; the app performs the action. */
	public interface Listener {
		void onSelectAgent(String agentId);

		void onNewAgent();

		/** Show what the agent is: identity, status, model, instructions, capabilities. */
		void onAgentInfo(String agentId);

		/** Delete the agent outright — record, conversations and memory. Already confirmed. */
		void onDeleteAgent(String agentId);
	}

	private static final int WIDTH = 158;

	private final Listener listener;
	private final JPanel rows = new JPanel();
	private String selectedId;
	private String defaultId;

	public AgentList(Listener listener) {
		super(new BorderLayout());
		this.listener = listener;
		setOpaque(false);
		setPreferredSize(new Dimension(WIDTH, 0));
		setMinimumSize(new Dimension(0, 0));
		setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, uiColor("Separator.foreground", Color.GRAY)));

		JLabel header = new JLabel("Agents");
		header.putClientProperty("FlatLaf.styleClass", "small");
		header.setForeground(muted());
		JButton add = new JButton("New agent", Lucide.icon("plus", 16, uiColor("Button.foreground", Color.WHITE)));
		add.putClientProperty("JButton.buttonType", "roundRect");
		add.setFocusPainted(false);
		add.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		add.setToolTipText("Create a new agent");
		add.addActionListener(e -> listener.onNewAgent());

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 10));
		top.add(header, BorderLayout.WEST);

		rows.setOpaque(false);
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		JPanel rowsHolder = new JPanel(new BorderLayout());
		rowsHolder.setOpaque(false);
		rowsHolder.add(rows, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(rowsHolder,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);

		add(top, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setOpaque(false);
		bottom.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
		bottom.add(add, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
	}

	/** Replace the agent list, highlighting {@code selectedId}; {@code defaultId} is the standard agent, which can't be deleted. */
	public void setAgents(List<AgentRef> agents, String selectedId, String defaultId) {
		this.selectedId = selectedId;
		this.defaultId = defaultId;
		rows.removeAll();
		for (AgentRef a : agents) rows.add(agentRow(a));
		rows.revalidate();
		rows.repaint();
	}

	/** One agent: a {@link PressButton} row, selected when it is the one open. */
	private Component agentRow(AgentRef agent) {
		PressButton row = new PressButton(agent.name());
		row.setHorizontalAlignment(SwingConstants.LEFT);
		row.setMargin(new Insets(9, 12, 9, 12));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		row.setSelected(agent.id().equals(selectedId));
		row.onPress(() -> listener.onSelectAgent(agent.id()));
		row.onPopup(() -> menuFor(agent));
		return row;
	}

	/** The right-click menu for one agent. */
	JPopupMenu menuFor(AgentRef agent) {
		String aid = agent.id();
		JPopupMenu menu = new JPopupMenu();

		JMenuItem open = new JMenuItem("Open");
		open.setEnabled(!aid.equals(selectedId));
		open.addActionListener(e -> listener.onSelectAgent(aid));
		menu.add(open);

		JMenuItem info = new JMenuItem("Agent info…");
		info.addActionListener(e -> listener.onAgentInfo(aid));
		menu.add(info);

		menu.addSeparator();

		JMenuItem delete = new JMenuItem("Delete…");
		delete.setEnabled(!aid.equals(defaultId));
		delete.addActionListener(e -> {
			int choice = JOptionPane.showConfirmDialog(this,
				"Delete " + agent.name() + "? Its conversations and memory will be removed. This can't be undone.",
				"Delete agent", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice == JOptionPane.OK_OPTION) listener.onDeleteAgent(aid);
		});
		menu.add(delete);

		return menu;
	}

	private static Color muted() {
		return uiColor("Label.disabledForeground", Color.GRAY);
	}

	private static Color uiColor(String key, Color fallback) {
		Color c = UIManager.getColor(key);
		return (c != null) ? c : fallback;
	}
}
