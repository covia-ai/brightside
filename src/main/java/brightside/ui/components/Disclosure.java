package brightside.ui.components;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.function.Supplier;

import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import com.formdev.flatlaf.util.UIScale;

/**
 * A collapsible section: a header row that folds a body away or opens it. The
 * header is a {@link PressButton} — it acts on the press, the theme paints its
 * hover — with a disclosure chevron before the caller's content and room for a
 * trailing chip. Used for the inbox cards, the chat's "N tool steps" chip and
 * each tool step inside it.
 *
 * <p>The header spans the section's width unless {@link #compact()} keeps it to
 * its content. {@link #onToggle} lets a host column re-lay out around a fold.
 */
@SuppressWarnings("serial")
public class Disclosure extends JPanel {

	private static final int CHEVRON = 16;

	private final PressButton header;
	private final JLabel chevron = new JLabel();
	private final Icon open;
	private final Icon closed;
	private final JComponent body;
	private boolean expanded;
	private boolean compact;
	private Runnable onToggle;

	public Disclosure(JComponent headerContent, JComponent body) {
		this(headerContent, body, CHEVRON);
	}

	/** {@code chevronSize} in unscaled pixels: 16 for a card header, 12 beside small text. */
	public Disclosure(JComponent headerContent, JComponent body, int chevronSize) {
		this.body = body;
		this.open = Lucide.icon("chevron-down", chevronSize, Theme::muted);
		this.closed = Lucide.icon("chevron-right", chevronSize, Theme::muted);
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);

		header = new PressButton("") {
			@Override
			public Dimension getMaximumSize() {
				Dimension p = getPreferredSize();
				return compact ? p : new Dimension(Integer.MAX_VALUE, p.height);
			}
		};
		header.setLayout(new BorderLayout(UIScale.scale(8), 0));
		header.setHorizontalAlignment(PressButton.LEFT);
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.add(chevron, BorderLayout.WEST);
		header.add(headerContent, BorderLayout.CENTER);
		header.onPress(this::toggle);

		body.setAlignmentX(LEFT_ALIGNMENT);
		add(header);
		add(body);
		apply();
	}

	/** The header button — for its margin, tooltip or a right-click menu. */
	public PressButton header() {
		return header;
	}

	/** A chip at the trailing end of the header (a status, a count). */
	public Disclosure trailing(JComponent chip) {
		header.add(chip, BorderLayout.EAST);
		return this;
	}

	/** The header takes only the width its content needs. */
	public Disclosure compact() {
		compact = true;
		revalidate();
		return this;
	}

	/** A right-click menu on the header, built on demand. */
	public Disclosure onPopup(Supplier<JPopupMenu> menu) {
		header.onPopup(menu);
		return this;
	}

	/** Runs after every fold or unfold, so a host can reflow. */
	public Disclosure onToggle(Runnable action) {
		this.onToggle = action;
		return this;
	}

	public boolean isExpanded() {
		return expanded;
	}

	public void setExpanded(boolean expanded) {
		if (this.expanded == expanded) return;
		this.expanded = expanded;
		apply();
		if (onToggle != null) onToggle.run();
	}

	public void toggle() {
		setExpanded(!expanded);
	}

	private void apply() {
		body.setVisible(expanded);
		chevron.setIcon(expanded ? open : closed);
		revalidate();
		repaint();
	}

	@Override
	public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}
}
