package brightside.ui.settings;

import static brightside.BrightSide.APP_NAME;

import java.awt.BorderLayout;

import javax.swing.BorderFactory;
import javax.swing.JPanel;

import brightside.ui.components.Buttons;
import brightside.ui.components.Labels;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
import net.miginfocom.swing.MigLayout;

/**
 * Settings → Peers: how your Tau meets others. What exists today is shown
 * (the map of the homes and people your Tau knows, and your identity to copy);
 * finding others, follow requests, and relaying through others are said to be
 * coming, never faked.
 */
@SuppressWarnings("serial")
public final class PeersSettingsPanel extends JPanel {

	public interface Host {
		void openPeersMap();

		void copyMyIdentity();
	}

	public PeersSettingsPanel(Host host) {
		super(new BorderLayout());
		JPanel form = new JPanel(new MigLayout("insets 24 28 20 28, fillx, wrap 1", "[grow,fill]", "[]6[]18[]6[]18[]6[]"));
		form.setOpaque(false);

		form.add(Styles.classes(Labels.text("The people and homes your " + APP_NAME + " knows"), Styles.SECTION));
		form.add(SelectableText.description("Your home and the people and agents in it, on the Peers map. "
			+ "Other homes on the map are examples for now.").small(), "wmin 0, w 10::");
		JPanel row = new JPanel(new MigLayout("insets 0, gap 8", "[][]", ""));
		row.setOpaque(false);
		javax.swing.JButton map = Buttons.secondary("Open the Peers map");
		map.addActionListener(e -> host.openPeersMap());
		row.add(map);
		javax.swing.JButton copy = Buttons.secondary("Copy my identity");
		copy.setToolTipText("Copies your identity, for someone who wants to reach you");
		copy.addActionListener(e -> host.copyMyIdentity());
		row.add(copy);
		form.add(row, "gaptop 6");

		form.add(Styles.classes(Labels.text("Finding and following others"), Styles.SECTION));
		form.add(SelectableText.description("Coming soon: finding other people's agents, follow requests you "
			+ "approve, and reaching others through a relay. Nothing here connects to anyone yet.").small(),
			"wmin 0, w 10::");

		form.add(Styles.classes(Labels.text("Who can reach you"), Styles.SECTION));
		form.add(SelectableText.description(APP_NAME + " listens on this computer only. Agents you connect get "
			+ "a named key with the access you choose, in Vault → Access.").small(), "wmin 0, w 10::");

		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.add(form, BorderLayout.NORTH);
		wrap.setBorder(BorderFactory.createEmptyBorder());
		add(brightside.ui.components.Scrolls.vertical(wrap), BorderLayout.CENTER);
	}
}
