package covia.brightside.ui.onboarding;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.Random;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultComboBoxModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import covia.brightside.Identity;
import covia.brightside.model.Providers;
import covia.brightside.ui.Icons;
import covia.brightside.vault.Mnemonic;

/**
 * The first-run onboarding wizard — the app's first impression. A sequence of
 * full-window steps: welcome, choose a passphrase (the encrypted vault), create
 * or import an identity (a BIP39 recovery phrase), pick a name, and choose a
 * model provider + API key. It only <em>collects</em> the choices; the vault,
 * identity write and venue launch happen in {@code BrightSide} once
 * {@link Listener#onComplete} fires. See {@code docs/ONBOARDING.md}.
 */
@SuppressWarnings("serial")
public final class OnboardingWizard extends JPanel {

	/** Everything onboarding gathers. {@code apiKey}/{@code providerId} are null for the offline bot. */
	public record Setup(char[] passphrase, String seedHex, String name,
			String providerId, String modelId, String apiKey) {
	}

	public interface Listener {
		void onComplete(Setup setup);
	}

	private enum Step {
		WELCOME, PASSPHRASE, IDENTITY, RECOVERY, CONFIRM, IMPORT, NAME, PROVIDER
	}

	private final Listener listener;

	private final JLabel titleLabel = OnboardingUI.title("");
	private final JLabel subtitleLabel = OnboardingUI.subtitle("");
	private final JPanel cards = new JPanel(new CardLayout());
	private final JButton back = OnboardingUI.secondary("Back");
	private final JButton next = OnboardingUI.primary("Continue");
	private final JLabel error = OnboardingUI.caption(" ");
	private final OnboardingUI.Dots dots = new OnboardingUI.Dots();

	// State captured across steps.
	private final JPasswordField pass1 = new JPasswordField(22);
	private final JPasswordField pass2 = new JPasswordField(22);
	private final OnboardingUI.Strength strength = new OnboardingUI.Strength();
	private boolean createNew = true;
	private String mnemonic;
	private int confirmA, confirmB;
	private final JTextField confirmFieldA = new JTextField(14);
	private final JTextField confirmFieldB = new JTextField(14);
	private final JTextArea importArea = new JTextArea(3, 30);
	private final JLabel importStatus = OnboardingUI.caption(" ");
	private final JTextField nameField = new JTextField(16);
	private final JComboBox<Providers.Provider> providerCombo = new JComboBox<>();
	private final JComboBox<Providers.Model> modelCombo = new JComboBox<>();
	private final JPasswordField keyField = new JPasswordField(30);
	private final JLabel keyLink = OnboardingUI.link("Get an API key →", Providers.defaultProvider().consoleUrl());
	private final JRadioButton useKey = new JRadioButton("Use my own model provider", true);
	private final JRadioButton useOffline = new JRadioButton("Skip for now — use the offline echo bot");

	private Step step = Step.WELCOME;

	public OnboardingWizard(Listener listener) {
		super(new BorderLayout());
		this.listener = listener;

		add(header(), BorderLayout.NORTH);
		add(centre(), BorderLayout.CENTER);
		add(footer(), BorderLayout.SOUTH);

		cards.setOpaque(false);
		cards.add(welcomeCard(), Step.WELCOME.name());
		cards.add(passphraseCard(), Step.PASSPHRASE.name());
		cards.add(identityCard(), Step.IDENTITY.name());
		cards.add(recoveryCard(), Step.RECOVERY.name());
		cards.add(confirmCard(), Step.CONFIRM.name());
		cards.add(importCard(), Step.IMPORT.name());
		cards.add(nameCard(), Step.NAME.name());
		cards.add(providerCard(), Step.PROVIDER.name());

		back.addActionListener(e -> goBack());
		next.addActionListener(e -> goNext());
		show(Step.WELCOME);
	}

	// ------------------------------------------------------------------
	// Chrome
	// ------------------------------------------------------------------

	private Component header() {
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(BorderFactory.createEmptyBorder(28, 0, 4, 0));
		JLabel mark = new JLabel(new ImageIcon(Icons.icon(56)));
		mark.setAlignmentX(CENTER_ALIGNMENT);
		JLabel word = new JLabel("Brightside");
		word.setForeground(OnboardingUI.muted());
		word.putClientProperty("FlatLaf.styleClass", "small");
		word.setAlignmentX(CENTER_ALIGNMENT);
		p.add(mark);
		p.add(Box.createVerticalStrut(6));
		p.add(word);
		return p;
	}

	private Component centre() {
		JPanel wrap = new JPanel(new GridBagLayout());
		wrap.setOpaque(false);
		JPanel col = new JPanel();
		col.setOpaque(false);
		col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
		col.setBorder(BorderFactory.createEmptyBorder(8, 32, 8, 32));
		titleLabel.setAlignmentX(CENTER_ALIGNMENT);
		subtitleLabel.setAlignmentX(CENTER_ALIGNMENT);
		cards.setAlignmentX(CENTER_ALIGNMENT);
		col.add(titleLabel);
		col.add(Box.createVerticalStrut(10));
		col.add(subtitleLabel);
		col.add(Box.createVerticalStrut(22));
		col.add(cards);
		wrap.add(col);
		return wrap;
	}

	private Component footer() {
		JPanel p = new JPanel();
		p.setOpaque(false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBorder(BorderFactory.createEmptyBorder(0, 0, 22, 0));
		error.setAlignmentX(CENTER_ALIGNMENT);
		error.setHorizontalAlignment(SwingConstants.CENTER);
		dots.setAlignmentX(CENTER_ALIGNMENT);
		JPanel buttons = new JPanel();
		buttons.setOpaque(false);
		buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
		buttons.setAlignmentX(CENTER_ALIGNMENT);
		buttons.add(back);
		buttons.add(Box.createHorizontalStrut(10));
		buttons.add(next);
		p.add(error);
		p.add(Box.createVerticalStrut(10));
		p.add(buttons);
		p.add(Box.createVerticalStrut(16));
		p.add(dots);
		return p;
	}

	// ------------------------------------------------------------------
	// Step cards
	// ------------------------------------------------------------------

	private JComponent welcomeCard() {
		JPanel c = column();
		c.add(OnboardingUI.html("Your own agent, on your own machine, under your own identity. "
			+ "Nothing leaves this computer except the model calls you ask for.<br><br>"
			+ "This takes about a minute.", 440, SwingConstants.CENTER));
		return c;
	}

	private JComponent passphraseCard() {
		JPanel c = column();
		pass1.putClientProperty("JTextField.placeholderText", "Passphrase");
		pass2.putClientProperty("JTextField.placeholderText", "Confirm passphrase");
		big(pass1);
		big(pass2);
		pass1.getDocument().addDocumentListener((Simple) e -> strength.set(OnboardingUI.scorePassphrase(pass1.getPassword())));
		c.add(pass1);
		c.add(Box.createVerticalStrut(10));
		c.add(pass2);
		c.add(Box.createVerticalStrut(16));
		c.add(strength);
		c.add(Box.createVerticalStrut(16));
		JLabel warn = OnboardingUI.html("⚠  Keep your recovery phrase safe. It can reset a forgotten passphrase "
			+ "and reopen retained Brightside data; provider API keys must be entered again.", 420, SwingConstants.CENTER);
		warn.setForeground(OnboardingUI.muted());
		warn.putClientProperty("FlatLaf.styleClass", "small");
		c.add(warn);
		return c;
	}

	private JComponent identityCard() {
		JPanel c = column();
		JRadioButton create = new JRadioButton("Create a new identity", true);
		JRadioButton importer = new JRadioButton("Import an existing recovery phrase");
		for (JRadioButton r : new JRadioButton[] { create, importer }) {
			r.setOpaque(false);
			r.setAlignmentX(CENTER_ALIGNMENT);
			r.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		}
		ButtonGroup g = new ButtonGroup();
		g.add(create);
		g.add(importer);
		create.addActionListener(e -> createNew = true);
		importer.addActionListener(e -> createNew = false);
		c.add(create);
		c.add(Box.createVerticalStrut(8));
		c.add(importer);
		return c;
	}

	private JPanel recoveryGrid = new JPanel(new GridLayout(4, 3, 10, 10));

	private JComponent recoveryCard() {
		JPanel c = column();
		recoveryGrid.setOpaque(false);
		recoveryGrid.setMaximumSize(new Dimension(460, 240));
		JButton copy = OnboardingUI.secondary("Copy");
		copy.addActionListener(e -> {
			if (mnemonic != null) {
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(mnemonic), null);
				setError("Copied to the clipboard.", false);
			}
		});
		copy.setAlignmentX(CENTER_ALIGNMENT);
		c.add(recoveryGrid);
		c.add(Box.createVerticalStrut(14));
		c.add(copy);
		return c;
	}

	private JComponent confirmCard() {
		JPanel c = column();
		big(confirmFieldA);
		big(confirmFieldB);
		JLabel la = OnboardingUI.caption("");
		JLabel lb = OnboardingUI.caption("");
		la.putClientProperty("confirm", "a");
		lb.putClientProperty("confirm", "b");
		JPanel rowA = labelled(la, confirmFieldA);
		JPanel rowB = labelled(lb, confirmFieldB);
		this.confirmLabelA = la;
		this.confirmLabelB = lb;
		c.add(rowA);
		c.add(Box.createVerticalStrut(12));
		c.add(rowB);
		return c;
	}

	private JLabel confirmLabelA, confirmLabelB;

	private JComponent importCard() {
		JPanel c = column();
		importArea.setLineWrap(true);
		importArea.setWrapStyleWord(true);
		importArea.putClientProperty("JTextArea.placeholderText", "your twelve or twenty-four words…");
		importArea.setFont(importArea.getFont().deriveFont(importArea.getFont().getSize2D() + 2f));
		javax.swing.JScrollPane sp = new javax.swing.JScrollPane(importArea);
		sp.setPreferredSize(new Dimension(440, 90));
		sp.setMaximumSize(new Dimension(440, 90));
		sp.setAlignmentX(CENTER_ALIGNMENT);
		importArea.getDocument().addDocumentListener((Simple) e -> validateImport());
		c.add(sp);
		c.add(Box.createVerticalStrut(10));
		c.add(importStatus);
		return c;
	}

	private JComponent nameCard() {
		JPanel c = column();
		nameField.setHorizontalAlignment(JTextField.CENTER);
		nameField.setFont(nameField.getFont().deriveFont(nameField.getFont().getSize2D() + 6f));
		nameField.setMaximumSize(new Dimension(320, nameField.getPreferredSize().height + 12));
		nameField.setAlignmentX(CENTER_ALIGNMENT);
		nameField.putClientProperty("JTextField.placeholderText", "Your name");
		c.add(nameField);
		return c;
	}

	private JComponent providerCard() {
		JPanel c = column();
		useKey.setOpaque(false);
		useOffline.setOpaque(false);
		useKey.setAlignmentX(CENTER_ALIGNMENT);
		useOffline.setAlignmentX(CENTER_ALIGNMENT);
		ButtonGroup g = new ButtonGroup();
		g.add(useKey);
		g.add(useOffline);

		providerCombo.setModel(new DefaultComboBoxModel<>(Providers.ALL.toArray(new Providers.Provider[0])));
		providerCombo.setRenderer(comboRenderer(p -> ((Providers.Provider) p).label()));
		modelCombo.setRenderer(comboRenderer(m -> ((Providers.Model) m).label()));
		providerCombo.addActionListener(e -> onProviderChanged());
		onProviderChanged();
		big(keyField);
		keyField.putClientProperty("JTextField.placeholderText", "Paste your API key");

		JPanel picker = new JPanel();
		picker.setOpaque(false);
		picker.setLayout(new BoxLayout(picker, BoxLayout.X_AXIS));
		picker.setAlignmentX(CENTER_ALIGNMENT);
		picker.add(labelledInline("Provider", providerCombo));
		picker.add(Box.createHorizontalStrut(14));
		picker.add(labelledInline("Model", modelCombo));

		JPanel keyBox = new JPanel();
		keyBox.setOpaque(false);
		keyBox.setLayout(new BoxLayout(keyBox, BoxLayout.Y_AXIS));
		keyBox.setAlignmentX(CENTER_ALIGNMENT);
		keyBox.add(keyField);
		keyBox.add(Box.createVerticalStrut(6));
		keyBox.add(keyLink);

		useKey.addActionListener(e -> setKeyEnabled(true));
		useOffline.addActionListener(e -> setKeyEnabled(false));

		c.add(useKey);
		c.add(Box.createVerticalStrut(12));
		c.add(picker);
		c.add(Box.createVerticalStrut(12));
		c.add(keyBox);
		c.add(Box.createVerticalStrut(16));
		c.add(useOffline);
		return c;
	}

	// ------------------------------------------------------------------
	// Navigation
	// ------------------------------------------------------------------

	private void show(Step s) {
		step = s;
		setError(" ", false);
		((CardLayout) cards.getLayout()).show(cards, s.name());
		switch (s) {
			case WELCOME -> set("Welcome to Brightside", "Your assistant. Yours alone.", "Get started", true);
			case PASSPHRASE -> set("Secure your Brightside",
				"Everything Brightside remembers is encrypted on this computer with a passphrase only you know.",
				"Continue", true);
			case IDENTITY -> set("Your identity",
				"A key that's yours alone — what makes this <i>your</i> agent, not an account on someone's server.",
				"Continue", true);
			case RECOVERY -> {
				set("Your recovery phrase",
					"Write these words down and keep them safe. They're the only way to restore your identity elsewhere.",
					"I've saved it", true);
				fillRecovery();
			}
			case CONFIRM -> {
				set("Confirm your phrase", "Just checking you saved it correctly.", "Continue", true);
				prepConfirm();
			}
			case IMPORT -> set("Import your recovery phrase",
				"Paste the 12 or 24 words from another Brightside, or any Convex BIP39 phrase.", "Continue", true);
			case NAME -> {
				set("What should I call you?", "So your assistant can address you properly.", "Continue", true);
				nameField.requestFocusInWindow();
			}
			case PROVIDER -> set("Choose your assistant's brain",
				"Brightside thinks using a model you choose. Your key is stored encrypted, only on this computer.",
				"Finish", true);
		}
		dots.set(5, phase(s));
		back.setVisible(s != Step.WELCOME);
	}

	private static int phase(Step s) {
		return switch (s) {
			case WELCOME -> 0;
			case PASSPHRASE -> 1;
			case IDENTITY, RECOVERY, CONFIRM, IMPORT -> 2;
			case NAME -> 3;
			case PROVIDER -> 4;
		};
	}

	private void goNext() {
		String err = validate(step);
		if (err != null) {
			setError(err, true);
			return;
		}
		switch (step) {
			case WELCOME -> show(Step.PASSPHRASE);
			case PASSPHRASE -> show(Step.IDENTITY);
			case IDENTITY -> show(createNew ? Step.RECOVERY : Step.IMPORT);
			case RECOVERY -> show(Step.CONFIRM);
			case CONFIRM, IMPORT -> show(Step.NAME);
			case NAME -> show(Step.PROVIDER);
			case PROVIDER -> finish();
		}
	}

	private void goBack() {
		switch (step) {
			case PASSPHRASE -> show(Step.WELCOME);
			case IDENTITY -> show(Step.PASSPHRASE);
			case RECOVERY -> show(Step.IDENTITY);
			case CONFIRM -> show(Step.RECOVERY);
			case IMPORT -> show(Step.IDENTITY);
			case NAME -> show(createNew ? Step.CONFIRM : Step.IMPORT);
			case PROVIDER -> show(Step.NAME);
			default -> { }
		}
	}

	private String validate(Step s) {
		switch (s) {
			case PASSPHRASE -> {
				char[] a = pass1.getPassword();
				char[] b = pass2.getPassword();
				if (a.length < 8) return "Use at least 8 characters.";
				if (!java.util.Arrays.equals(a, b)) return "The passphrases don't match.";
			}
			case RECOVERY -> {
				if (mnemonic == null) mnemonic = Mnemonic.generate(Mnemonic.DEFAULT_WORDS);
			}
			case CONFIRM -> {
				String[] w = mnemonic.split(" ");
				if (!confirmFieldA.getText().trim().equalsIgnoreCase(w[confirmA - 1])
					|| !confirmFieldB.getText().trim().equalsIgnoreCase(w[confirmB - 1])) {
					return "Those words don't match — check your phrase.";
				}
			}
			case IMPORT -> {
				String reason = Mnemonic.checkReason(importArea.getText());
				if (reason != null) return "That's not a valid recovery phrase.";
			}
			case NAME -> {
				if (Identity.sanitise(nameField.getText()).isEmpty()) return "Please enter a name.";
			}
			case PROVIDER -> {
				if (useKey.isSelected() && Providers.byId(providerId()).needsApiKey()
					&& keyField.getPassword().length == 0) {
					return "Paste your API key, or choose the offline bot.";
				}
			}
			default -> { }
		}
		return null;
	}

	private void finish() {
		char[] passphrase = pass1.getPassword();
		String seedHex = Mnemonic.toSeedHex(createNew ? mnemonic : importArea.getText());
		String name = nameField.getText().trim();
		boolean offline = useOffline.isSelected();
		String provider = offline ? null : providerId();
		String model = offline ? null : ((Providers.Model) modelCombo.getSelectedItem()).id();
		String apiKey = (offline || keyField.getPassword().length == 0) ? null : new String(keyField.getPassword());
		listener.onComplete(new Setup(passphrase, seedHex, name, provider, model, apiKey));
	}

	// ------------------------------------------------------------------
	// Step helpers
	// ------------------------------------------------------------------

	private void fillRecovery() {
		if (mnemonic == null) mnemonic = Mnemonic.generate(Mnemonic.DEFAULT_WORDS);
		recoveryGrid.removeAll();
		String[] w = mnemonic.split(" ");
		for (int i = 0; i < w.length; i++) recoveryGrid.add(OnboardingUI.wordChip(i + 1, w[i]));
		recoveryGrid.revalidate();
		recoveryGrid.repaint();
	}

	private void prepConfirm() {
		Random r = new Random();
		int n = mnemonic.split(" ").length;
		confirmA = 1 + r.nextInt(n);
		do {
			confirmB = 1 + r.nextInt(n);
		} while (confirmB == confirmA);
		if (confirmB < confirmA) {
			int t = confirmA;
			confirmA = confirmB;
			confirmB = t;
		}
		confirmLabelA.setText("Word #" + confirmA);
		confirmLabelB.setText("Word #" + confirmB);
		confirmFieldA.setText("");
		confirmFieldB.setText("");
	}

	private void validateImport() {
		String text = importArea.getText().trim();
		if (text.isEmpty()) {
			importStatus.setText(" ");
			return;
		}
		boolean ok = Mnemonic.isValid(text);
		importStatus.setText(ok ? "✓ valid phrase" : "not a valid phrase yet…");
		importStatus.setForeground(ok ? new Color(0x3F, 0xB9, 0x50) : OnboardingUI.muted());
	}

	private void onProviderChanged() {
		Providers.Provider p = (Providers.Provider) providerCombo.getSelectedItem();
		if (p == null) return;
		modelCombo.setModel(new DefaultComboBoxModel<>(p.models().toArray(new Providers.Model[0])));
		keyLink.setText("<html><a href=''>Get a " + p.label() + " key →</a></html>");
		for (var l : keyLink.getMouseListeners()) keyLink.removeMouseListener(l);
		keyLink.addMouseListener(new java.awt.event.MouseAdapter() {
			@Override
			public void mouseClicked(java.awt.event.MouseEvent e) {
				OnboardingUI.open(p.consoleUrl());
			}
		});
		boolean needsKey = p.needsApiKey();
		if (useKey.isSelected()) setKeyEnabled(true);
		keyField.setVisible(needsKey);
		keyLink.setVisible(needsKey);
	}

	private void setKeyEnabled(boolean on) {
		Providers.Provider p = (Providers.Provider) providerCombo.getSelectedItem();
		boolean needsKey = on && p != null && p.needsApiKey();
		keyField.setEnabled(on);
		providerCombo.setEnabled(on);
		modelCombo.setEnabled(on);
		keyField.setVisible(needsKey);
		keyLink.setVisible(needsKey);
	}

	private String providerId() {
		Providers.Provider p = (Providers.Provider) providerCombo.getSelectedItem();
		return (p != null) ? p.id() : Providers.defaultProvider().id();
	}

	// ------------------------------------------------------------------
	// Small UI utilities
	// ------------------------------------------------------------------

	private void set(String title, String subtitleHtml, String nextText, boolean showNext) {
		titleLabel.setText(title);
		subtitleLabel.setText("<html><div style='text-align:center; width:440px;'>" + subtitleHtml + "</div></html>");
		next.setText(nextText);
		next.setVisible(showNext);
	}

	private void setError(String message, boolean isError) {
		error.setText(message);
		error.setForeground(isError ? new Color(0xE5, 0x53, 0x53) : OnboardingUI.muted());
	}

	private static JPanel column() {
		JPanel c = new JPanel();
		c.setOpaque(false);
		c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
		c.setAlignmentX(CENTER_ALIGNMENT);
		return c;
	}

	private static void big(JComponent field) {
		field.setFont(field.getFont().deriveFont(field.getFont().getSize2D() + 3f));
		field.setMaximumSize(new Dimension(360, field.getPreferredSize().height + 10));
		field.setAlignmentX(CENTER_ALIGNMENT);
	}

	private static JPanel labelled(JLabel label, JComponent field) {
		JPanel row = new JPanel();
		row.setOpaque(false);
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setAlignmentX(CENTER_ALIGNMENT);
		label.setAlignmentX(CENTER_ALIGNMENT);
		row.add(label);
		row.add(Box.createVerticalStrut(4));
		row.add(field);
		return row;
	}

	private static JPanel labelledInline(String labelText, JComponent field) {
		JPanel row = new JPanel();
		row.setOpaque(false);
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		JLabel l = OnboardingUI.caption(labelText);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		field.setAlignmentX(Component.LEFT_ALIGNMENT);
		field.setMaximumSize(new Dimension(190, field.getPreferredSize().height + 6));
		row.add(l);
		row.add(Box.createVerticalStrut(4));
		row.add(field);
		return row;
	}

	private static javax.swing.ListCellRenderer<Object> comboRenderer(java.util.function.Function<Object, String> text) {
		javax.swing.DefaultListCellRenderer base = new javax.swing.DefaultListCellRenderer();
		return (list, value, index, selected, focus) -> {
			Component comp = base.getListCellRendererComponent(list, value, index, selected, focus);
			if (value != null && comp instanceof JLabel jl) jl.setText(text.apply(value));
			return comp;
		};
	}

	/** A DocumentListener whose three methods collapse to one callback. */
	@FunctionalInterface
	private interface Simple extends DocumentListener {
		void update(DocumentEvent e);

		@Override
		default void insertUpdate(DocumentEvent e) {
			update(e);
		}

		@Override
		default void removeUpdate(DocumentEvent e) {
			update(e);
		}

		@Override
		default void changedUpdate(DocumentEvent e) {
			update(e);
		}
	}
}
