package brightside.ui.shell;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;

import com.formdev.flatlaf.util.UIScale;

import brightside.ui.components.Lucide;
import brightside.ui.components.Styles;
import brightside.ui.components.Theme;
import brightside.ui.components.Tone;

/**
 * The status strip across the top of the content: who you are acting as
 * (click for the identity menu), whether this computer's venue is running
 * (its local address on hover, a click copies it), the count of requests
 * waiting (a click opens the Inbox), and search (the command palette). On the
 * chrome ground: gold 200 in Light, a step up from the field in Dark.
 */
@SuppressWarnings("serial")
public final class StatusStrip extends JPanel {

	public interface Host {
		void showIdentityMenu(java.awt.Component from);

		void copyLocalAddress();

		void openInbox();

		void openPalette();
	}

	/** This computer's venue. */
	public enum Venue {
		STARTING("Starting", Tone.MUTED), RUNNING("Running", Tone.SUCCESS), STOPPED("Stopped", Tone.MUTED);

		final String word;
		final Tone tone;

		Venue(String word, Tone tone) {
			this.word = word;
			this.tone = tone;
		}
	}

	private final JButton acting = chip();
	private final JButton computer = chip();
	private final JButton waiting = chip();
	private final JButton search = chip();
	private Venue venue = Venue.STARTING;
	private String address;
	private boolean operator;

	public StatusStrip(Host host) {
		setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
		setOpaque(true);
		acting.addActionListener(e -> host.showIdentityMenu(acting));
		computer.addActionListener(e -> host.copyLocalAddress());
		waiting.addActionListener(e -> host.openInbox());
		search.addActionListener(e -> host.openPalette());
		search.setIcon(Lucide.icon("search", 14, Theme::muted));
		search.setText("Search");
		Styles.classes(search, Styles.SMALL, Styles.MUTED);
		search.getAccessibleContext().setAccessibleName("Search: open the command palette");
		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, UIScale.scale(6), 0));
		left.setOpaque(false);
		left.add(acting);
		left.add(computer);
		add(left);
		add(Box.createHorizontalGlue());
		add(waiting);
		add(Box.createHorizontalStrut(UIScale.scale(6)));
		add(search);
		setActing(null, false);
		setVenue(Venue.STARTING, null);
		setWaiting(0);
	}

	private static JButton chip() {
		JButton b = new JButton();
		b.putClientProperty("JButton.buttonType", "toolBarButton");
		b.setFocusable(true);
		Styles.classes(b, Styles.SMALL);
		return b;
	}

	@Override
	public Color getBackground() {
		return Theme.chrome();
	}

	@Override
	public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	/** Who you are acting as; {@code operator} when acting as the venue operator. */
	public void setActing(String name, boolean operator) {
		this.operator = operator;
		String who = (name == null) ? "Not signed in" : name;
		acting.setText(operator ? "Acting as " + who + " (operator)" : "Acting as " + who);
		acting.setIcon(Lucide.icon(operator ? "building-2" : "user-round", 14, () -> this.operator ? Theme.yours() : Theme.foreground()));
		acting.setToolTipText("Who " + brightside.BrightSide.APP_NAME + " acts as: click to switch user, copy your identity, or lock");
		acting.getAccessibleContext().setAccessibleName(acting.getText());
	}

	public void setVenue(Venue venue, String address) {
		this.venue = venue;
		this.address = address;
		computer.setText("This computer · " + venue.word);
		computer.setIcon(Lucide.icon("hard-drive", 14, () -> this.venue.tone.colour()));
		computer.setToolTipText((address != null) ? "Running locally at " + address + ": click to copy the address"
			: "This computer's " + brightside.BrightSide.APP_NAME + ": " + venue.word.toLowerCase());
		computer.getAccessibleContext().setAccessibleName(computer.getText());
	}

	public Venue venue() {
		return venue;
	}

	public String address() {
		return address;
	}

	public void setWaiting(int count) {
		waiting.setVisible(count > 0);
		waiting.setText((count > 99 ? "99+" : Integer.toString(count)) + " waiting");
		waiting.setIcon(Lucide.icon("hourglass", 14, Theme::foreground));
		waiting.setToolTipText("Requests waiting for your decision: open the Inbox");
		waiting.getAccessibleContext().setAccessibleName(count + " waiting: open the Inbox");
	}

	/** The search chip shows its shortcut once the window knows it. */
	public void setSearchKey(String keyText) {
		search.setText("Search  " + keyText);
		search.setToolTipText("Jump to any screen, chat, agent, setting, or action (" + keyText + ")");
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		g.setColor(Theme.line());
		g.fillRect(0, getHeight() - 1, getWidth(), 1);
	}
}
