package brightside.ui.components;

import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.UIManager;

import com.formdev.flatlaf.ui.FlatUIUtils;

/**
 * A single line of text that always fits the width it is given by eliding
 * its middle — {@code did:key:z6Mk…:u:mike} — so an opaque value (a DID, a
 * key, a token) never wraps or forces its row wider. Given room it shows the
 * whole value. The full value is its tooltip unless told otherwise (a secret),
 * and {@link #copyable} adds a right-click <em>Copy</em> of it.
 */
@SuppressWarnings("serial")
public class ElidedText extends JLabel {

	private static final String ELLIPSIS = "…";

	private boolean showsTooltip;

	public ElidedText(String text) {
		super(text);
		Labels.plain(this);
		showsTooltip = true;
		setToolTipText(text);
	}

	/** An opaque value in the monospaced face. */
	public static ElidedText mono(String text) {
		return Styles.classes(new ElidedText(text), Styles.MONOSPACED);
	}

	/** Whether the full value shows on hover (off for anything secret). */
	public ElidedText tooltip(boolean show) {
		showsTooltip = show;
		setToolTipText(show ? getText() : null);
		return this;
	}

	/** A right-click menu with <em>Copy</em>, which takes the full value. */
	public ElidedText copyable() {
		addMouseListener(new MouseAdapter() {
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
				JPopupMenu menu = new JPopupMenu();
				JMenuItem copy = new JMenuItem("Copy");
				copy.addActionListener(a -> Clipboard.copy(getText()));
				menu.add(copy);
				menu.show(ElidedText.this, e.getX(), e.getY());
			}
		});
		return this;
	}

	@Override
	public void setText(String text) {
		super.setText(text);
		if (showsTooltip) setToolTipText(text);
	}

	/** Can shrink to just the ellipsis; the preferred size (the full value) is the label's own. */
	@Override
	public Dimension getMinimumSize() {
		Insets in = getInsets();
		FontMetrics fm = getFontMetrics(getFont());
		return new Dimension(fm.stringWidth(ELLIPSIS) + in.left + in.right, super.getMinimumSize().height);
	}

	@Override
	protected void paintComponent(Graphics g) {
		if (isOpaque()) {
			g.setColor(getBackground());
			g.fillRect(0, 0, getWidth(), getHeight());
		}
		String full = getText();
		if (full == null || full.isEmpty()) return;
		Insets in = getInsets();
		FontMetrics fm = getFontMetrics(getFont());
		String shown = fit(full, fm, getWidth() - in.left - in.right);
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setFont(getFont());
			g2.setColor(isEnabled() ? getForeground() : UIManager.getColor("Label.disabledForeground"));
			int y = in.top + (getHeight() - in.top - in.bottom - fm.getHeight()) / 2 + fm.getAscent();
			FlatUIUtils.drawString(this, g2, shown, in.left, y);
		} finally {
			g2.dispose();
		}
	}

	/** The longest head…tail of {@code s} that fits {@code width}, or the whole of it. */
	static String fit(String s, FontMetrics fm, int width) {
		if (fm.stringWidth(s) <= width) return s;
		int lo = 0;
		int hi = s.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			if (fm.stringWidth(elide(s, mid)) <= width) lo = mid;
			else hi = mid - 1;
		}
		return (lo == 0) ? ELLIPSIS : elide(s, lo);
	}

	/** {@code keep} characters of {@code s}, the head getting the odd one, around an ellipsis. */
	private static String elide(String s, int keep) {
		int head = (keep + 1) / 2;
		int tail = keep - head;
		return s.substring(0, head) + ELLIPSIS + s.substring(s.length() - tail);
	}
}
