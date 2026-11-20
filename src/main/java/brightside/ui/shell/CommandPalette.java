package brightside.ui.shell;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Labels;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;

/**
 * The command palette: type to find any screen, Settings page, menu item, or
 * action in {@link Commands}, with where it lives and its shortcut on each
 * row, so it also teaches the layout. Enter runs the selected row, the arrows
 * move, Esc closes. Shown over the window in its layered pane, keeping focus
 * inside while open.
 */
@SuppressWarnings("serial")
public final class CommandPalette extends JPanel {

	private final Commands commands;
	private final JTextField field = new JTextField();
	private final DefaultListModel<Commands.Command> model = new DefaultListModel<>();
	private final JList<Commands.Command> list = new JList<>(model);
	private JRootPane root;

	public CommandPalette(Commands commands) {
		super(new BorderLayout(0, UIScale.scale(6)));
		this.commands = commands;
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Jump to a screen, setting, or action");
		field.getAccessibleContext().setAccessibleName("Command palette search");
		Styles.style(field, "font: +2");
		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new Row());
		list.setVisibleRowCount(9);
		list.setFocusable(false);
		list.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				int i = list.locationToIndex(e.getPoint());
				if (i >= 0) run(model.get(i));
			}
		});
		JScrollPane scroll = new JScrollPane(list);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		add(field, BorderLayout.NORTH);
		add(scroll, BorderLayout.CENTER);

		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) { refilter(); }
			@Override public void removeUpdate(DocumentEvent e) { refilter(); }
			@Override public void changedUpdate(DocumentEvent e) { refilter(); }
		});
		key("ESCAPE", this::close);
		key("ENTER", () -> {
			Commands.Command c = list.getSelectedValue();
			if (c != null) run(c);
		});
		key("DOWN", () -> move(1));
		key("UP", () -> move(-1));
		// Focus stays inside while open.
		field.setFocusTraversalKeysEnabled(false);
	}

	private void key(String stroke, Runnable r) {
		field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke), stroke);
		field.getActionMap().put(stroke, new AbstractAction() {
			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				r.run();
			}
		});
	}

	private void move(int by) {
		int n = model.size();
		if (n == 0) return;
		int i = Math.floorMod(list.getSelectedIndex() + by, n);
		list.setSelectedIndex(i);
		list.ensureIndexIsVisible(i);
	}

	void refilter() {
		model.clear();
		List<Commands.Command> hits = commands.search(field.getText());
		for (Commands.Command c : hits) model.addElement(c);
		if (!model.isEmpty()) list.setSelectedIndex(0);
	}

	/** Opens over {@code root}'s window, with the field empty and focused. */
	public void open(JRootPane root) {
		this.root = root;
		field.setText("");
		refilter();
		JLayeredPane layers = root.getLayeredPane();
		if (getParent() != layers) layers.add(this, JLayeredPane.POPUP_LAYER);
		int w = Math.min(UIScale.scale(640), layers.getWidth() - UIScale.scale(32));
		Dimension pref = getPreferredSize();
		setBounds((layers.getWidth() - w) / 2, UIScale.scale(64), w, Math.min(pref.height, layers.getHeight() - UIScale.scale(96)));
		setVisible(true);
		layers.revalidate();
		layers.repaint();
		SwingUtilities.invokeLater(field::requestFocusInWindow);
	}

	public boolean isOpen() {
		return isVisible() && getParent() != null;
	}

	public void close() {
		setVisible(false);
		if (getParent() != null) {
			java.awt.Container p = getParent();
			p.remove(this);
			p.repaint();
		}
	}

	private void run(Commands.Command c) {
		close();
		SwingUtilities.invokeLater(c.action());
	}

	/** For tests: the rows shown for {@code query}. */
	public List<Commands.Command> rowsFor(String query) {
		field.setText(query);
		return java.util.Collections.list(model.elements());
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			FlatUIUtils.setRenderingHints(g2);
			int arc = UIScale.scale(24);
			g2.setColor(new Color(0, 0, 0, 60));
			g2.fillRoundRect(2, 4, getWidth() - 4, getHeight() - 4, arc, arc);
			g2.setColor(Theme.card());
			g2.fillRoundRect(0, 0, getWidth() - 2, getHeight() - 4, arc, arc);
			g2.setColor(Theme.line());
			g2.drawRoundRect(0, 0, getWidth() - 3, getHeight() - 5, arc, arc);
		} finally {
			g2.dispose();
		}
	}

	/** A row: name, then where it lives, with the shortcut on the right. */
	private static final class Row extends JPanel implements ListCellRenderer<Commands.Command> {
		private final JLabel name = Labels.text("");
		private final JLabel where = Labels.small("");
		private final JLabel key = Labels.small("");

		Row() {
			super(new BorderLayout(UIScale.scale(12), 0));
			setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
			JPanel text = new JPanel(new BorderLayout());
			text.setOpaque(false);
			text.add(name, BorderLayout.NORTH);
			text.add(where, BorderLayout.SOUTH);
			Styles.classes(where, Styles.SMALL, Styles.MUTED);
			Styles.classes(key, Styles.SMALL, Styles.MUTED);
			add(text, BorderLayout.CENTER);
			add(key, BorderLayout.EAST);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends Commands.Command> l, Commands.Command c, int index,
				boolean selected, boolean focus) {
			name.setText(c.name());
			where.setText(c.where());
			key.setText(c.key() != null ? Rail.keyText(c.key()) : "");
			setOpaque(true);
			setBackground(selected ? Theme.warmSurface() : Theme.card());
			name.setForeground(Theme.foreground());
			return this;
		}
	}
}
