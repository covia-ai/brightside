package brightside.ui.inbox;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

import brightside.Inbox;
import brightside.ui.LAF;
import brightside.ui.Lucide;
import brightside.ui.PressButton;

/**
 * One request as a card in the Inbox column: a rounded, tinted panel — open
 * requests carry the accent tint, resolved ones sit quietly — whose header
 * (chevron, title, who and when, a status chip) collapses or expands the body,
 * the {@link RequestForm}. Cards are what keep long requests readable and
 * separate: each is its own bordered block, and only the ones you are dealing
 * with need to be open.
 */
@SuppressWarnings("serial")
final class RequestCard extends JPanel {

	private static final int ARC = 14;

	private final Inbox.Request request;
	private final RequestForm form;
	private final PressButton header;
	private final JLabel chevron = new JLabel();
	private boolean expanded;

	RequestCard(Inbox.Request request, RequestForm.Listener listener, boolean expanded) {
		this.request = request;
		this.expanded = expanded;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setAlignmentX(LEFT_ALIGNMENT);
		setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));

		header = new PressButton("");
		header.setLayout(new BorderLayout(10, 0));
		header.setMargin(new Insets(10, 12, 10, 12));
		header.setAlignmentX(LEFT_ALIGNMENT);
		header.setHorizontalAlignment(PressButton.LEFT);
		header.setToolTipText(request.open() ? "Show or hide this request" : "Show or hide the resolved request");

		JLabel title = new JLabel(request.title());
		title.putClientProperty("html.disable", Boolean.TRUE);
		title.setFont(title.getFont().deriveFont(request.open() ? Font.BOLD : Font.PLAIN, 14f));
		JLabel meta = new JLabel(RequestForm.meta(request));
		meta.putClientProperty("html.disable", Boolean.TRUE);
		meta.putClientProperty("FlatLaf.styleClass", "small");
		meta.setForeground(muted());
		JPanel text = new JPanel();
		text.setOpaque(false);
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		title.setAlignmentX(LEFT_ALIGNMENT);
		meta.setAlignmentX(LEFT_ALIGNMENT);
		text.add(title);
		text.add(meta);

		header.add(chevron, BorderLayout.WEST);
		header.add(text, BorderLayout.CENTER);
		header.add(chip(request), BorderLayout.EAST);
		header.onPress(this::toggle);

		form = new RequestForm(request, listener);
		form.setAlignmentX(LEFT_ALIGNMENT);
		form.setBorder(BorderFactory.createEmptyBorder(0, 18, 14, 18));

		add(header);
		add(form);
		apply();
	}

	Inbox.Request request() {
		return request;
	}

	RequestForm form() {
		return form;
	}

	boolean expanded() {
		return expanded;
	}

	void setExpanded(boolean expanded) {
		if (this.expanded == expanded) return;
		this.expanded = expanded;
		apply();
	}

	private void toggle() {
		setExpanded(!expanded);
	}

	private void apply() {
		form.setVisible(expanded);
		chevron.setIcon(Lucide.icon(expanded ? "chevron-down" : "chevron-right", 16, muted()));
		revalidate();
		repaint();
	}

	/** "Waiting for you" in the accent while open; otherwise the outcome, quietly. */
	private static JLabel chip(Inbox.Request r) {
		JLabel chip = new JLabel(r.open() ? "Waiting for you" : outcome(r));
		chip.putClientProperty("FlatLaf.styleClass", "small");
		chip.setForeground(r.open() ? LAF.ACCENT : muted());
		chip.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
		return chip;
	}

	private static String outcome(Inbox.Request r) {
		return switch (r.status() == null ? "" : r.status()) {
			case "answered" -> "Answered";
			case "rejected" -> "Rejected";
			case "expired" -> "Expired";
			case "cancelled" -> "Withdrawn";
			default -> r.status();
		};
	}

	@Override
	public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	/** A rounded card: an accent tint while the request is open, a faint one once resolved, and a hairline edge. */
	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Color base = panel();
		Color fill = request.open() ? mix(base, LAF.ACCENT, 0.10f) : mix(base, foreground(), 0.03f);
		g2.setColor(fill);
		g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
		g2.setColor(request.open() ? mix(base, LAF.ACCENT, 0.45f) : line());
		g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
		g2.dispose();
		super.paintComponent(g);
	}

	private static Color mix(Color a, Color b, float t) {
		return new Color(
			Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
			Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
	}

	private static Color panel() {
		Color c = UIManager.getColor("Panel.background");
		return (c != null) ? c : Color.DARK_GRAY;
	}

	private static Color foreground() {
		Color c = UIManager.getColor("Label.foreground");
		return (c != null) ? c : Color.LIGHT_GRAY;
	}

	private static Color muted() {
		Color c = UIManager.getColor("Label.disabledForeground");
		return (c != null) ? c : Color.GRAY;
	}

	private static Color line() {
		Color c = UIManager.getColor("Separator.foreground");
		return (c != null) ? c : Color.GRAY;
	}
}
