package brightside.ui.onboarding;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.util.Random;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingConstants;

import com.formdev.flatlaf.FlatClientProperties;

import brightside.Identity;
import brightside.model.Providers;
import brightside.ui.Icons;
import brightside.ui.components.Buttons;
import brightside.ui.components.Clipboard;
import brightside.ui.components.Documents;
import brightside.ui.components.Labels;
import brightside.ui.components.Links;
import brightside.ui.components.ModelSelector;
import brightside.ui.components.Panels;
import brightside.ui.components.PressButton;
import brightside.ui.components.Styles;
import brightside.ui.components.TextArea;
import brightside.vault.Mnemonic;

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

	/** The width the app's own copy wraps at on these screens. */
	private static final int COPY_WIDTH = 440;

	private final Listener listener;

	private final JLabel titleLabel = Labels.title("");
	private final JLabel subtitleLabel = Styles.classes(Labels.html("", COPY_WIDTH, SwingConstants.CENTER), Styles.MUTED);
	private final JPanel cards = new JPanel(new CardLayout());
	private final JButton back = Buttons.secondary("Back");
	private final JButton next = Buttons.primary("Continue");
	private final JLabel error = Labels.small(" ");
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
	private final TextArea importArea = new TextArea(3, 30).placeholder("your twelve or twenty-four words…");
	private final JLabel importStatus = Labels.small(" ");
	private final JTextField nameField = new JTextField(16);
	private final ModelSelector modelSelector = new ModelSelector();
	private final JPasswordField keyField = new JPasswordField(30);
	private final PressButton keyLink = Buttons.link("Get an API key →", Providers.defaultProvider().consoleUrl());
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
		JPanel p = column();
		p.setBorder(BorderFactory.createEmptyBorder(28, 0, 4, 0));
		JLabel mark = Labels.icon(new ImageIcon(Icons.icon(56)));
		mark.setAlignmentX(CENTER_ALIGNMENT);
		JLabel word = Labels.small("Brightside");
		word.setAlignmentX(CENTER_ALIGNMENT);
		p.add(mark);
		p.add(Box.createVerticalStrut(6));
		p.add(word);
		return p;
	}

	private Component centre() {
		JPanel wrap = new JPanel(new GridBagLayout());
		wrap.setOpaque(false);
		JPanel col = column();
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
		JPanel p = column();
		p.setBorder(BorderFactory.createEmptyBorder(0, 0, 22, 0));
		error.setAlignmentX(CENTER_ALIGNMENT);
		error.setHorizontalAlignment(SwingConstants.CENTER);
		dots.setAlignmentX(CENTER_ALIGNMENT);
		JPanel buttons = Panels.row();
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
		c.add(copy("Your own agent, on your own machine, under your own identity. "
			+ "Nothing leaves this computer except the model calls you ask for.<br><br>"
			+ "This takes about a minute."));
		return c;
	}

	private JComponent passphraseCard() {
		JPanel c = column();
		pass1.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Passphrase");
		pass2.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Confirm passphrase");
		big(pass1);
		big(pass2);
		Documents.onChange(pass1, () -> strength.set(OnboardingUI.scorePassphrase(pass1.getPassword())));
		c.add(pass1);
		c.add(Box.createVerticalStrut(10));
		c.add(pass2);
		c.add(Box.createVerticalStrut(16));
		c.add(strength);
		c.add(Box.createVerticalStrut(16));
		JLabel warn = copy("⚠  Keep your recovery phrase safe. It can reset a forgotten passphrase "
			+ "and reopen retained Brightside data; provider API keys must be entered again.");
		Styles.classes(warn, Styles.SMALL, Styles.MUTED);
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
			r.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
		JButton copy = Buttons.secondary("Copy");
		copy.addActionListener(e -> {
			if (mnemonic != null) {
				Clipboard.copy(mnemonic);
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
		JLabel la = Labels.small("");
		JLabel lb = Labels.small("");
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
		Styles.style(importArea, "font: +2");
		JScrollPane sp = new JScrollPane(importArea);
		sp.setPreferredSize(new Dimension(440, 90));
		sp.setMaximumSize(new Dimension(440, 90));
		sp.setAlignmentX(CENTER_ALIGNMENT);
		Documents.onChange(importArea, this::validateImport);
		c.add(sp);
		c.add(Box.createVerticalStrut(10));
		c.add(importStatus);
		return c;
	}

	private JComponent nameCard() {
		JPanel c = column();
		nameField.setHorizontalAlignment(JTextField.CENTER);
		Styles.style(nameField, "font: +6");
		nameField.setMaximumSize(new Dimension(320, nameField.getPreferredSize().height + 12));
		nameField.setAlignmentX(CENTER_ALIGNMENT);
		nameField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Your name");
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

		modelSelector.addSelectionListener(this::onProviderChanged);
		onProviderChanged();
		big(keyField);
		Styles.style(keyField, "font: +3 $monospaced.font");
		keyField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Paste your API key");

		modelSelector.setAlignmentX(CENTER_ALIGNMENT);
		modelSelector.setMaximumSize(new Dimension(420, modelSelector.getPreferredSize().height));

		JPanel keyBox = column();
		keyBox.add(keyField);
		keyBox.add(Box.createVerticalStrut(6));
		keyLink.setAlignmentX(CENTER_ALIGNMENT);
		keyBox.add(keyLink);

		useKey.addActionListener(e -> setKeyEnabled(true));
		useOffline.addActionListener(e -> setKeyEnabled(false));

		c.add(useKey);
		c.add(Box.createVerticalStrut(12));
		c.add(modelSelector);
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
		Providers.Model selectedModel = modelSelector.selectedModel();
		String model = offline || selectedModel == null ? null : selectedModel.id();
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
		Styles.classes(importStatus, Styles.SMALL, ok ? Styles.SUCCESS : Styles.MUTED);
	}

	private void onProviderChanged() {
		Providers.Provider p = modelSelector.selectedProvider();
		if (p == null) return;
		keyLink.setText("Get a " + p.label() + " key →");
		keyLink.onPress(() -> Links.open(p.consoleUrl()));
		boolean needsKey = p.needsApiKey();
		if (useKey.isSelected()) setKeyEnabled(true);
		keyField.setVisible(needsKey);
		keyLink.setVisible(needsKey);
	}

	private void setKeyEnabled(boolean on) {
		Providers.Provider p = modelSelector.selectedProvider();
		boolean needsKey = on && p != null && p.needsApiKey();
		keyField.setEnabled(on);
		modelSelector.setEnabled(on);
		keyField.setVisible(needsKey);
		keyLink.setVisible(needsKey);
	}

	private String providerId() {
		Providers.Provider p = modelSelector.selectedProvider();
		return (p != null) ? p.id() : Providers.defaultProvider().id();
	}

	// ------------------------------------------------------------------
	// Small UI utilities
	// ------------------------------------------------------------------

	private void set(String title, String subtitleHtml, String nextText, boolean showNext) {
		titleLabel.setText(title);
		subtitleLabel.setText(Labels.wrap(subtitleHtml, COPY_WIDTH, SwingConstants.CENTER));
		next.setText(nextText);
		next.setVisible(showNext);
	}

	private void setError(String message, boolean isError) {
		error.setText(message);
		Styles.classes(error, Styles.SMALL, isError ? Styles.ERROR : Styles.MUTED);
	}

	/** The app's own copy for a step, centred and wrapped. */
	private static JLabel copy(String html) {
		JLabel l = Labels.html(html, COPY_WIDTH, SwingConstants.CENTER);
		l.setAlignmentX(CENTER_ALIGNMENT);
		return l;
	}

	private static JPanel column() {
		JPanel c = Panels.column();
		c.setAlignmentX(CENTER_ALIGNMENT);
		return c;
	}

	private static void big(JComponent field) {
		Styles.style(field, "font: +3");
		field.setMaximumSize(new Dimension(360, field.getPreferredSize().height + 10));
		field.setAlignmentX(CENTER_ALIGNMENT);
	}

	private static JPanel labelled(JLabel label, JComponent field) {
		JPanel row = column();
		label.setAlignmentX(CENTER_ALIGNMENT);
		row.add(label);
		row.add(Box.createVerticalStrut(4));
		row.add(field);
		return row;
	}
}
