package brightside.ui.agents;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Buttons;
import brightside.ui.components.Density;
import brightside.ui.components.Dialogs;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.ScreenHeader;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * Agents: every agent this computer's venue knows, which Tau coordinates under
 * your identity. Your own agents, the venue's own agents (when acting as the
 * operator), and outside agents that reach the venue over MCP with a named
 * token. Columns: agent and id, kind, where its instance lives, state, and
 * grant. Tabs: All, Yours, Outside. "Add an agent" is the view's one primary
 * action. Agents are shown by name and id, never with a face.
 *
 * <p>Dumb: the window supplies the rows and performs every action.
 */
@SuppressWarnings("serial")
public final class AgentsScreen extends JPanel {

	/** What a row is. */
	public enum Kind {
		YOURS("Yours"), VENUE("Built in"), OUTSIDE_MCP("Connected · MCP");

		public final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	/**
	 * One agent.
	 *
	 * @param id     the agent id (or the token's name for an outside agent)
	 * @param name   its display name
	 * @param kind   what it is
	 * @param venue  where its instance lives, in words
	 * @param state  in words
	 * @param grant  what it may do, in words
	 * @param role   for the venue's own agents, its role ("the administrator"); else null
	 * @param did    its identity (an agent's DID), from which its mark and fingerprint come; null when it has none to show
	 */
	public record Row(String id, String name, Kind kind, String venue, String state, String grant, String role, String did) {

		public Row(String id, String name, Kind kind, String venue, String state, String grant, String role) {
			this(id, name, kind, venue, state, grant, role, null);
		}
	}

	public interface Host {
		void open(Row agent);

		void info(Row agent);

		/** Already confirmed. */
		void delete(Row agent);

		void createHere();

		/** Issue a named token for an outside agent: Settings → Access. */
		void connectOutside();

		void manageAccess(Row agent);

		void copy(String text);
	}

	private enum Filter {
		ALL("All"), YOURS("Yours"), OUTSIDE("Connected");

		final String label;

		Filter(String label) {
			this.label = label;
		}
	}

	private static final String[] COLUMNS = { "Agent", "Kind", "Lives on", "State", "Access" };

	private final Host host;
	private final Model model = new Model();
	private final JTable table = new JTable(model);
	private final JLabel empty = Labels.small(" ");
	private final brightside.ui.components.PressButton bringIn;
	private final JPanel tableArea = new JPanel(new BorderLayout());
	private final JScrollPane scroll;
	private List<Row> all = List.of();
	private String defaultId;
	private Filter filter = Filter.ALL;

	public AgentsScreen(Host host) {
		super(new BorderLayout());
		this.host = host;

		JButton add = Buttons.primary("Add an agent");
		add.setIcon(Lucide.icon("plus", 16, () -> java.awt.Color.WHITE));
		add.setToolTipText("Create an agent here, or bring in one you already use");
		add.addActionListener(e -> addMenu().show(add, 0, add.getHeight()));
		ScreenHeader header = new ScreenHeader("Agents");
		header.action(add);

		JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(4), 0));
		tabs.setOpaque(false);
		tabs.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
		ButtonGroup group = new ButtonGroup();
		for (Filter f : Filter.values()) {
			JToggleButton t = new JToggleButton(f.label, f == filter);
			if (f == Filter.OUTSIDE) t.setIcon(Lucide.icon("cable", 14, Theme::blue));
			t.putClientProperty("JButton.buttonType", "tab");
			t.addActionListener(e -> {
				filter = f;
				refilter();
			});
			group.add(t);
			tabs.add(t);
		}
		JLabel note = Labels.small("Agents over A2A and peers' agents are coming.");
		Styles.classes(note, Styles.SMALL, Styles.MUTED);
		tabs.add(note);

		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.setShowVerticalLines(false);
		table.setFillsViewportHeight(true);
		table.getTableHeader().setReorderingAllowed(false);
		table.setDefaultRenderer(Object.class, new Cell());
		table.getAccessibleContext().setAccessibleName("Agents");
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && selected() != null) host.open(selected());
			}

			@Override
			public void mousePressed(MouseEvent e) {
				popup(e);
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				popup(e);
			}

			private void popup(MouseEvent e) {
				if (!e.isPopupTrigger()) return;
				int r = table.rowAtPoint(e.getPoint());
				if (r >= 0) table.setRowSelectionInterval(r, r);
				Row row = selected();
				if (row != null) menuFor(row).show(table, e.getX(), e.getY());
			}
		});
		table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
			.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open");
		table.getActionMap().put("open", new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				if (selected() != null) host.open(selected());
			}
		});

		JPanel body = new JPanel(new BorderLayout());
		body.setOpaque(false);
		body.add(tabs, BorderLayout.NORTH);
		scroll = new JScrollPane(table);
		scroll.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 20));
		tableArea.setOpaque(false);
		tableArea.add(scroll, BorderLayout.CENTER);
		body.add(tableArea, BorderLayout.CENTER);
		empty.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		// A quiet way in while the roster is all but empty.
		bringIn = Buttons.link("Add an agent to bring your other agents in", () -> addMenu().show(empty, 0, 0));
		JPanel foot = new JPanel(new BorderLayout());
		foot.setOpaque(false);
		foot.setBorder(BorderFactory.createEmptyBorder(4, 20, 12, 20));
		foot.add(empty, BorderLayout.NORTH);
		foot.add(bringIn, BorderLayout.WEST);
		tableArea.add(foot, BorderLayout.SOUTH);
		add(header, BorderLayout.NORTH);
		add(body, BorderLayout.CENTER);
	}

	/** Replace the rows; {@code defaultId} is the standard agent, which can't be deleted. */
	public void setRows(List<Row> rows, String defaultId) {
		this.all = List.copyOf(rows);
		this.defaultId = defaultId;
		refilter();
	}

	/** The rows on view, for tests. */
	public List<Row> visibleRows() {
		return model.rows;
	}

	private void refilter() {
		List<Row> out = new ArrayList<>();
		for (Row r : all) {
			if (filter == Filter.YOURS && r.kind() == Kind.OUTSIDE_MCP) continue;
			if (filter == Filter.OUTSIDE && r.kind() != Kind.OUTSIDE_MCP) continue;
			out.add(r);
		}
		model.rows = out;
		model.fireTableDataChanged();
		table.setRowHeight(UIScale.scale(Density.compact() ? 26 : 34));
		boolean sparse = all.size() <= 1 || (filter == Filter.OUTSIDE && out.isEmpty());
		bringIn.setVisible(sparse);
		// While it is all but empty the table hugs its rows, so the hint sits right under them.
		tableArea.removeAll();
		if (sparse) {
			table.setFillsViewportHeight(false);
			int h = table.getTableHeader().getPreferredSize().height + Math.max(1, out.size())
				* UIScale.scale(Density.compact() ? 26 : 34) + 4;
			scroll.setPreferredSize(new java.awt.Dimension(0, h));
			tableArea.add(scroll, BorderLayout.NORTH);
			JPanel below = new JPanel(new BorderLayout());
			below.setOpaque(false);
			below.add(foot(), BorderLayout.NORTH);
			tableArea.add(below, BorderLayout.CENTER);
		} else {
			table.setFillsViewportHeight(true);
			scroll.setPreferredSize(null);
			tableArea.add(scroll, BorderLayout.CENTER);
			tableArea.add(foot(), BorderLayout.SOUTH);
		}
		tableArea.revalidate();
		empty.setText(out.isEmpty() ? (filter == Filter.OUTSIDE
			? "No connected agents yet. Add an agent, then Connected · MCP, to give one a named token."
			: "No agents yet.") : " ");
	}

	private JPanel foot() {
		return (JPanel) bringIn.getParent();
	}

	private Row selected() {
		int r = table.getSelectedRow();
		return (r < 0) ? null : model.rows.get(table.convertRowIndexToModel(r));
	}

	/** The sources an agent can come from. Those that need the network say so. */
	JPopupMenu addMenu() {
		JPopupMenu menu = new JPopupMenu();
		JMenuItem here = new JMenuItem("Create here");
		here.setToolTipText("A new agent on this computer, with its own instructions and model");
		here.addActionListener(e -> host.createHere());
		menu.add(here);
		JMenuItem mcp = new JMenuItem("Connected · MCP: give it a named token");
		mcp.setIcon(Lucide.icon("cable", 14, Theme::blue));
		mcp.setToolTipText("For an agent you already use: a token with only the access you choose (Settings → Access)");
		mcp.addActionListener(e -> host.connectOutside());
		menu.add(mcp);
		JMenuItem a2a = new JMenuItem("Connected · A2A (coming)");
		a2a.setEnabled(false);
		menu.add(a2a);
		JMenuItem peer = new JMenuItem("A peer's agent (coming)");
		peer.setEnabled(false);
		menu.add(peer);
		return menu;
	}

	JPopupMenu menuFor(Row row) {
		JPopupMenu menu = new JPopupMenu();
		if (row.kind() == Kind.OUTSIDE_MCP) {
			JMenuItem manage = new JMenuItem("Manage its access");
			manage.addActionListener(e -> host.manageAccess(row));
			menu.add(manage);
		} else {
			JMenuItem open = new JMenuItem("Open");
			open.addActionListener(e -> host.open(row));
			menu.add(open);
			JMenuItem info = new JMenuItem("Agent info");
			info.addActionListener(e -> host.info(row));
			menu.add(info);
		}
		JMenuItem copy = new JMenuItem("Copy id");
		copy.addActionListener(e -> host.copy(row.id()));
		menu.add(copy);
		if (row.did() != null) {
			JMenuItem did = new JMenuItem("Copy Agent ID (DID)");
			did.addActionListener(e -> host.copy(row.did()));
			menu.add(did);
		}
		if (row.kind() == Kind.YOURS) {
			menu.addSeparator();
			JMenuItem delete = new JMenuItem("Delete…");
			delete.setEnabled(!row.id().equals(defaultId));
			delete.addActionListener(e -> {
				if (Dialogs.confirmDanger(this, "Delete agent",
					"Delete " + row.name() + "? Its conversations and memory will be removed. This can't be undone.")) {
					host.delete(row);
				}
			});
			menu.add(delete);
		}
		return menu;
	}

	private static final class Model extends AbstractTableModel {
		List<Row> rows = List.of();

		@Override
		public int getRowCount() {
			return rows.size();
		}

		@Override
		public int getColumnCount() {
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int c) {
			return COLUMNS[c];
		}

		@Override
		public Object getValueAt(int r, int c) {
			Row row = rows.get(r);
			return switch (c) {
				case 0 -> row;
				case 1 -> row.role() != null ? row.kind().label + " · " + row.role() : row.kind().label;
				case 2 -> row.venue();
				case 3 -> row.state();
				default -> row.grant();
			};
		}
	}

	/**
	 * The agent cell: its generated mark (a pattern, never a face), its display
	 * name, and, muted in mono, the short fingerprint of its Agent ID. Ids stay
	 * one step away (Copy id, Agent info). The rest is plain text on the card colour.
	 */
	private final class Cell extends DefaultTableCellRenderer {
		@Override
		public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int r,
				int c) {
			super.getTableCellRendererComponent(t, value, selected, focus, r, c);
			setIcon(null);
			if (value instanceof Row row) {
				String key = (row.did() != null) ? row.did() : row.kind() + ":" + row.id();
				setIcon(brightside.ui.components.IdentityMark.icon(key, 20));
				setIconTextGap(UIScale.scale(8));
				setText(row.did() == null ? row.name()
					: "<html>" + Labels.escape(row.name()) + "&nbsp;&nbsp;<span style='font-family:monospace;color:"
						+ hex(Theme.muted()) + "'>" + Labels.escape(brightside.ui.components.IdentityMark.fingerprint(row.did()))
						+ "</span></html>");
				setToolTipText(row.did() != null ? "Agent ID " + row.did() : null);
			}
			setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
			if (!selected) setBackground(Theme.card());
			setForeground(Theme.foreground());
			return this;
		}

		private String hex(java.awt.Color c) {
			return String.format("#%06X", c.getRGB() & 0xFFFFFF);
		}
	}
}
