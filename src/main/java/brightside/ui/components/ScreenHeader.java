package brightside.ui.components;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import net.miginfocom.swing.MigLayout;

/**
 * The bar along the top of a screen: its title, then anything that qualifies
 * it (a {@link Chip}: whose skills these are, that the map is a preview), and
 * the screen's own actions at the right, ruled off from the content below.
 * Screens that are one main pane (Skills, Peers, Inbox) wear one, so they read
 * alike; screens led by a sidebar (Sessions, Files, Settings) take their
 * bearings from the sidebar instead.
 */
@SuppressWarnings("serial")
public final class ScreenHeader extends JPanel {

	private final JLabel title;
	private boolean anyAction;

	public ScreenHeader(String title) {
		// The row is never shorter than an icon button, so a header with no
		// actions stands as tall as one with them.
		super(new MigLayout("insets 10 20 10 14, fillx, gapx 10, aligny center, hidemode 3", "", "[32::]"));
		setOpaque(false);
		setBorder(Borders.hairline(false, false, true, false));
		this.title = Labels.section(title);
		add(this.title);
	}

	public void setTitle(String text) {
		title.setText(text);
	}

	/** Something beside the title that qualifies it, such as a chip. Add these before the actions. */
	public ScreenHeader detail(JComponent c) {
		add(c);
		return this;
	}

	/** One of the screen's actions, at the right, after those added before it. */
	public ScreenHeader action(JComponent c) {
		add(c, anyAction ? "gapx 2" : "gapx push");
		anyAction = true;
		return this;
	}

	/** An action that starts a new group, set a little apart from the one before. */
	public ScreenHeader group(JComponent c) {
		add(c, anyAction ? "gapx 12" : "gapx push");
		anyAction = true;
		return this;
	}
}
