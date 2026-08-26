package covia.brightside.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;

import covia.brightside.model.AgentRef;
import covia.brightside.ui.LAF;

/**
 * The agents pane (left of the sessions list on the Sessions screen): one row per
 * agent the user owns — e.g. Brightside and Bob — each with its own conversations.
 * Selecting one switches the chat (and the sessions list) to that agent. A "New"
 * button creates another agent.
 *
 * <p>Dumb like {@link ConversationList}: it renders what it's given and reports
 * clicks through a {@link Listener}; {@code BrightSide} owns switching/creating.
 */
@SuppressWarnings("serial")
public final class AgentList extends JPanel {

	public interface Listener {
		void onSelectAgent(String agentId);

		void onNewAgent();
	}

	private static final int WIDTH = 158;

	private final Listener listener;
	private final JPanel rows = new JPanel();
	private String selectedId;

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
		JButton add = new JButton("+ New");
		add.putClientProperty("JButton.buttonType", "roundRect");
		add.putClientProperty("FlatLaf.styleClass", "small");
		add.setFocusPainted(false);
		add.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		add.setToolTipText("Create a new agent");
		add.addActionListener(e -> listener.onNewAgent());

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 10));
		top.add(header, BorderLayout.WEST);
		top.add(add, BorderLayout.EAST);

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
	}

	/** Replace the agent list, highlighting {@code selectedId}. */
	public void setAgents(List<AgentRef> agents, String selectedId) {
		this.selectedId = selectedId;
		rows.removeAll();
		for (AgentRef a : agents) rows.add(agentRow(a));
		rows.revalidate();
		rows.repaint();
	}

	private Component agentRow(AgentRef agent) {
		boolean selected = agent.id().equals(selectedId);
		JLabel row = new JLabel(agent.name());
		row.setOpaque(selected);
		if (selected) {
			row.setBackground(LAF.ACCENT);
			row.setForeground(Color.WHITE);
		} else {
			row.setForeground(uiColor("Label.foreground", Color.WHITE));
		}
		row.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + 18));
		row.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				listener.onSelectAgent(agent.id());
			}
		});
		return row;
	}

	private static Color muted() {
		return uiColor("Label.disabledForeground", Color.GRAY);
	}

	private static Color uiColor(String key, Color fallback) {
		Color c = UIManager.getColor(key);
		return (c != null) ? c : fallback;
	}
}
