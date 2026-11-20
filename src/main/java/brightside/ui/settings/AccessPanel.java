package brightside.ui.settings;

import static brightside.BrightSide.APP_NAME;

import java.util.List;
import java.util.function.Consumer;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;

import brightside.TokenPreset;
import brightside.Tokens;
import brightside.ui.binding.Watcher;
import brightside.ui.components.Buttons;
import brightside.ui.components.Clipboard;
import brightside.ui.components.Dialogs;
import brightside.ui.components.EyeButton;
import brightside.ui.components.Fields;
import brightside.ui.components.Labels;
import brightside.ui.components.Lucide;
import brightside.ui.components.SelectableText;
import brightside.ui.components.Styles;
import brightside.ui.components.TextArea;
import brightside.ui.components.Theme;
import brightside.ui.components.Times;
import brightside.ui.components.Toast;
import brightside.ui.components.Tone;
import convex.core.data.ACell;
import convex.core.data.AVector;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.util.JSON;
import convex.lattice.ALatticeComponent;
import net.miginfocom.swing.MigLayout;

/**
 * The <b>Access</b> settings page: every way another program reaches this
 * venue, each saying how it appears there. Its list is the owner's named
 * tokens ({@link Tokens}), followed live from the venue's token store, each
 * with its key (behind the passphrase, as a secret is), a new key and revoke.
 * <b>New access</b> starts with the kind: a named token for a tool, whose turns
 * name the token; a login as the owner, whose turns are the owner's; or a
 * login as the venue operator. A named token's key is kept by the venue and
 * shown again from the list; a login is shown once and kept nowhere. Below,
 * the login for local tools that {@link brightside.ToolToken} keeps fresh.
 */
@SuppressWarnings("serial")
public final class AccessPanel extends SettingsPage {

	/** One of the owner's tokens, as the list shows it; {@code unrestricted} when it can do all the owner can. */
	public record Row(String name, String scope, long created, String madeBy, boolean unrestricted) {}

	/** What the page can do; calls that may fail throw with a message fit to show. */
	public interface Host {
		/** The owner's tokens; empty while the venue is not up. */
		List<Row> tokens();

		/** The token store to follow, or null while the venue is not up. */
		ALatticeComponent<?> tokenStore();

		/** The owner's chat agent, which the presets name. */
		String chatAgentId();

		/** Makes a named token and returns its key. */
		String createToken(String name, Tokens.Scope scope) throws Exception;

		/** Gives a token a new key and returns it. */
		String newTokenKey(String name) throws Exception;

		void revokeToken(String name) throws Exception;

		/** A token's key, after re-authenticating with the passphrase, which is wiped. */
		String revealTokenKey(String name, char[] passphrase) throws Exception;

		/** The passphrase remembered in plaintext on this computer, a fresh copy (null: none); the page wipes it. */
		char[] rememberedPassphrase();

		/** A login for {@code seconds}, as the owner or the venue operator, or null when none can be made. */
		String mintLogin(long seconds, boolean asOperator);

		boolean keepingToolToken();

		void keepToolToken(boolean keep);
	}

	/** A kind of access, with how it appears to the venue. */
	private enum Kind {
		TOKEN("Named token for a tool (MCP)", "A key for one tool, with the tools and access you choose. "
			+ "Its turns show as the token's name, so you can tell them apart. Kept until you revoke it."),
		LOGIN("Login as you", "A login that appears as you, with everything you can do. "
			+ "Shown once and kept nowhere; it stops working when it expires."),
		OPERATOR("Operator login (advanced)", "A login as this computer's own account, for administration only. "
			+ "Shown once and kept nowhere; it stops working when it expires.");

		final String label;
		final String hint;

		Kind(String label, String hint) {
			this.label = label;
			this.hint = hint;
		}

		@Override
		public String toString() {
			return label;
		}
	}

	private record Exp(String label, long seconds) {
		@Override
		public String toString() {
			return label;
		}
	}

	/** The scope picker's entries: the presets, then custom. */
	private static final String CUSTOM = "Custom…";
	private static final String MASK = "••••••••••••";

	private final Host host;
	private final JPanel tokenList = new JPanel(new MigLayout(
		"insets 0, fillx, wrap 6, gapy 4", "[grow 25,fill]12[grow 75,fill]8[]2[]6[]4[]", ""));
	private final JComboBox<Kind> kind = new JComboBox<>(Kind.values());
	private final JLabel kindHint = Labels.allowHtml(Labels.small(" ")); // wraps its escaped hint
	private final JTextField name = new JTextField(16);
	private final JComboBox<Object> preset = new JComboBox<>();
	private final JLabel presetHint = Labels.allowHtml(Labels.small(" ")); // wraps its escaped hint
	private final TextArea tools = new TextArea(4, 34).placeholder("One tool per line, such as v/ops/covia/read");
	private final TextArea caps = new TextArea(4, 34).placeholder("\"unrestricted\", or a list of {\"with\", \"can\"}");
	private final JComboBox<Exp> expiry = new JComboBox<>();
	private final TextArea issued = new TextArea(3, 34).placeholder("The new key or login appears here");
	private final JButton copy = Buttons.small("Copy");
	private final JCheckBox keepToolToken = new JCheckBox("Keep a login as you for local tools");
	/** The form's parts for each kind, shown and hidden as one. */
	private Rows tokenRows, customRows, loginRows;
	private Watcher.Watch<?> watch;
	/** Bumped on every rebuild of the list, so a slow reveal never lands on replaced rows. */
	private long version;

	public AccessPanel(Host host) {
		super("Create");
		this.host = host;
		build();
		keepToolToken.setSelected(host.keepingToolToken());
		keepToolToken.addActionListener(e -> host.keepToolToken(keepToolToken.isSelected()));
	}

	private void build() {
		tokenList.setOpaque(false);
		group("Named tokens");
		addSpan(tokenList);

		group("New access");
		kind.addActionListener(e -> showKind());
		addField("Kind", kind);
		addSpan(kindHint);

		preset.addActionListener(e -> showPreset());
		tokenRows = rows(() -> {
			addField("Name", "Lower-case letters, digits, and hyphens, such as claude-code: the name its turns show", name);
			addField("Access", "What the token's tools are, and what they may reach", preset);
			addSpan(presetHint);
		});
		JScrollPane toolsScroll = new JScrollPane(tools);
		JScrollPane capsScroll = new JScrollPane(caps);
		customRows = rows(() -> {
			addFieldTop("Tools", toolsScroll);
			addFieldTop("Permissions", capsScroll);
		});
		Styles.classes(tools, Styles.MONOSPACED);
		Styles.classes(caps, Styles.MONOSPACED);

		expiry.setModel(new DefaultComboBoxModel<>(new Exp[] {
			new Exp("5 minutes", 300L),
			new Exp("1 hour", 3600L),
			new Exp("1 day", 86_400L),
			new Exp("30 days", 2_592_000L),
		}));
		expiry.setSelectedIndex(1);
		loginRows = rows(() -> addField("Expires in", "How long the login stays valid", expiry));

		issued.setEditable(false);
		issued.setLineWrap(true);
		Styles.classes(issued, Styles.MONOSPACED);
		JScrollPane issuedScroll = new JScrollPane(issued);
		addSpan(issuedScroll, "h 72!, gaptop 6");
		JPanel issuedRow = new JPanel(new MigLayout("insets 0", "[]8[]", ""));
		issuedRow.setOpaque(false);
		issuedRow.add(copy);
		issuedRow.add(primary);
		addSpanLeft(issuedRow);
		copy.setEnabled(false);
		copy.setToolTipText("Copy it to the clipboard");
		copy.addActionListener(e -> Clipboard.copy(issued.getText(), copy));
		primary.setToolTipText("Create the access chosen above");
		onPrimary(this::onCreate);

		keepToolToken.setOpaque(false);
		keepToolToken.setToolTipText("Renewed every hour while " + APP_NAME + " runs, and removed when it quits");
		group("Local tools");
		addField("Login", "While " + APP_NAME + " runs, keeps a login as you, valid for a day, in tool-token.json in "
			+ "your data folder, for tools on this computer such as Claude Code to read when they connect. Their turns "
			+ "show as you. Like a remembered passphrase, anyone who can read your files can use it.", keepToolToken);

		fillPresets();
		showKind();
	}

	private void fillPresets() {
		DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
		for (TokenPreset p : TokenPreset.values()) model.addElement(p);
		model.addElement(CUSTOM);
		preset.setModel(model);
		preset.setRenderer(new javax.swing.DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
					boolean selected, boolean focused) {
				super.getListCellRendererComponent(list, value, index, selected, focused);
				setIcon((value instanceof TokenPreset p && p.dangerous()) ? warningIcon() : null);
				return this;
			}
		});
		preset.setSelectedIndex(0);
	}

	private static javax.swing.Icon warningIcon() {
		return Lucide.icon("circle-alert", 14, Theme::warning);
	}

	/** Marks {@code label} as a warning: the warning sign, its colour, and why in its tooltip. */
	private static void warn(JLabel label, String why) {
		label.setIcon(warningIcon());
		Styles.classes(label, Styles.SMALL, Styles.WARNING);
		label.setToolTipText(why);
	}

	private void showKind() {
		Kind k = (Kind) kind.getSelectedItem();
		kindHint.setText("<html>" + Labels.escape(k.hint) + "</html>");
		boolean token = k == Kind.TOKEN;
		tokenRows.setVisible(token);
		loginRows.setVisible(!token);
		showPreset();
	}

	/** Shows the chosen preset's line; choosing custom opens its tools and caps, starting from the last preset. */
	private void showPreset() {
		boolean token = kind.getSelectedItem() == Kind.TOKEN;
		Object chosen = preset.getSelectedItem();
		boolean custom = CUSTOM.equals(chosen);
		presetHint.setIcon(null);
		Styles.classes(presetHint, Styles.SMALL, Styles.MUTED);
		presetHint.setToolTipText(null);
		if (chosen instanceof TokenPreset p) {
			presetHint.setText("<html>" + Labels.escape(p.description()) + "</html>");
			if (p.dangerous()) warn(presetHint, null);
			Tokens.Scope scope = p.scope(host.chatAgentId());
			tools.setText(String.join("\n", scope.tools().stream().map(String::valueOf).toList()));
			caps.setText(JSON.toStringPretty(scope.caps()));
		} else {
			presetHint.setText("Any tools, within the permissions you write; it starts from the preset you last chose.");
		}
		customRows.setVisible(token && custom);
		revalidate();
		repaint();
	}

	/** Follows the token store while the page shows, re-reading the list off the event thread on each change. */
	public void bind() {
		if (watch != null) watch.close();
		ALatticeComponent<?> store = host.tokenStore();
		watch = (store == null) ? null : Watcher.watch(tokenList, store, value -> reload());
		reload();
	}

	private void reload() {
		new SwingWorker<List<Row>, Void>() {
			@Override
			protected List<Row> doInBackground() {
				return host.tokens();
			}

			@Override
			protected void done() {
				try {
					show(get());
				} catch (Exception e) {
					show(List.of());
				}
			}
		}.execute();
	}

	private void show(List<Row> tokens) {
		version++;
		tokenList.removeAll();
		if (tokens.isEmpty()) {
			tokenList.add(Labels.small("No named tokens yet: make one below, under New access."), "span 6");
		} else {
			for (Row row : tokens) addRow(row);
		}
		tokenList.revalidate();
		tokenList.repaint();
	}

	private void addRow(Row row) {
		JLabel label = Styles.classes(new JLabel(row.name()), Styles.MONOSPACED);
		JLabel details = Labels.small(row.scope() + " · made " + Times.absolute(row.created()) + " by " + row.madeBy()
			+ " · until revoked");
		if (row.unrestricted()) warn(details, "It can do everything you can: keep its key like your passphrase");
		SelectableText key = new SelectableText(MASK).mono().muted();
		EyeButton eye = new EyeButton("Show the key (behind your passphrase)", "Hide the key", 16);
		JButton copyKey = Buttons.icon(Lucide.icon("copy", 16, Theme::muted),
			"Copy the key (behind your passphrase while it is hidden)");
		JButton renew = Buttons.small("New key");
		renew.setToolTipText("Give this token a new key; the old one stops working");
		JButton revoke = Buttons.small("Revoke");
		revoke.setToolTipText("Stop this token working, on work it already started too");
		final String[] shown = { null };

		eye.addActionListener(e -> {
			if (shown[0] != null) {
				shown[0] = null;
				key.setText(MASK);
				eye.setShown(false);
				return;
			}
			fetchGated(row.name(), "show the key of", eye, k -> {
				shown[0] = k;
				key.setText(k);
				eye.setShown(true);
				setNote("Shown for this sitting only.", false);
			});
		});
		copyKey.addActionListener(e -> {
			if (shown[0] != null) {
				Clipboard.copy(shown[0], copyKey);
				return;
			}
			fetchGated(row.name(), "copy the key of", copyKey, k -> {
				Clipboard.copy(k);
				clearNote();
				Toast.show(copyKey, "Copied without showing it", "check", Tone.SUCCESS);
			});
		});
		renew.addActionListener(e -> {
			if (!Dialogs.confirmDanger(this, "New key", "Give \"" + row.name() + "\" a new key? "
				+ "The tool using it stops working until you paste the new key into it.")) return;
			act(() -> host.newTokenKey(row.name()), k -> showIssued(k,
				"New key for " + row.name() + ". Paste it into the tool in place of the old one."));
		});
		revoke.addActionListener(e -> {
			if (!Dialogs.confirmDanger(this, "Revoke token", "Revoke \"" + row.name() + "\"? "
				+ "Its key stops working at once, on work it already started too.")) return;
			act(() -> {
				host.revokeToken(row.name());
				return null;
			}, k -> setNote("Revoked " + row.name() + ".", false));
		});

		tokenList.add(label, "wmin 0");
		tokenList.add(key, "growx, wmin 0");
		tokenList.add(eye);
		tokenList.add(copyKey);
		tokenList.add(renew);
		tokenList.add(revoke);
		tokenList.add(details, "span 6, gaptop 0, gapbottom 8, wmin 0");
	}

	private void onCreate() {
		Kind k = (Kind) kind.getSelectedItem();
		if (k != Kind.TOKEN) {
			Exp sel = (Exp) expiry.getSelectedItem();
			String jwt = host.mintLogin(sel.seconds(), k == Kind.OPERATOR);
			if (jwt == null) {
				clearIssued();
				setNote("Couldn't make a login: " + brightside.BrightSide.APP_NAME + " is still starting. Nothing was made; try again in a moment.", true);
				return;
			}
			showIssued(jwt, "A login " + (k == Kind.OPERATOR ? "as the operator" : "as you")
				+ ", valid for " + sel.label() + ". Treat it like a password: it isn't kept.");
			return;
		}
		String tokenName = name.getText().trim();
		if (!tokenName.matches("[a-z0-9-]{1,64}")) {
			setNote("A token's name is lower-case letters, digits, and hyphens.", true);
			Fields.flag(name);
			return;
		}
		Tokens.Scope scope;
		try {
			scope = chosenScope();
		} catch (IllegalArgumentException e) {
			setNote(e.getMessage(), true);
			return;
		}
		act(() -> host.createToken(tokenName, scope), key -> {
			name.setText("");
			showIssued(key, "Made " + tokenName + ". Paste the key into the tool's MCP settings as "
				+ "Authorization: Bearer; it is shown again from the list.");
		});
	}

	/** The scope chosen: a preset's, or the custom tools and caps as written. */
	private Tokens.Scope chosenScope() {
		Object chosen = preset.getSelectedItem();
		if (chosen instanceof TokenPreset p) return p.scope(host.chatAgentId());
		AVector<ACell> ops = Vectors.empty();
		for (String line : tools.getText().split("\\R")) {
			if (!line.isBlank()) ops = ops.conj(Strings.create(line.trim()));
		}
		if (ops.isEmpty()) throw new IllegalArgumentException("A custom token needs at least one tool.");
		ACell parsed;
		try {
			parsed = JSON.parse(caps.getText().trim());
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("The permissions aren't valid JSON: " + e.getMessage(), e);
		}
		if (parsed == null) throw new IllegalArgumentException("A custom token needs its permissions: \"unrestricted\" or a list.");
		return new Tokens.Scope(ops, parsed);
	}

	/** Runs {@code call} off the event thread, then hands its result to {@code then}, or shows its failure. */
	private void act(java.util.concurrent.Callable<String> call, Consumer<String> then) {
		primary.setEnabled(false);
		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() throws Exception {
				return call.call();
			}

			@Override
			protected void done() {
				primary.setEnabled(true);
				try {
					then.accept(get());
				} catch (java.util.concurrent.ExecutionException e) {
					String why = e.getCause().getMessage();
					setNote((why == null) ? "That didn't work." : why, true);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
				reload();
			}
		}.execute();
	}

	private void showIssued(String key, String what) {
		issued.setText(key);
		issued.setCaretPosition(0);
		copy.setEnabled(true);
		setNote(what, false);
	}

	private void clearIssued() {
		issued.setText("");
		copy.setEnabled(false);
	}

	/**
	 * The passphrase gate for a token's key, as for a secret: prompts unless
	 * the passphrase is remembered on this computer, re-authenticates off the
	 * event thread, and hands the key to {@code use}.
	 */
	private void fetchGated(String token, String verb, JButton trigger, Consumer<String> use) {
		char[] entered = host.rememberedPassphrase();
		if (entered == null) {
			JPasswordField pass = new JPasswordField(24);
			if (!Dialogs.confirmDanger(this,
				"Enter your " + APP_NAME + " passphrase to " + verb + " \"" + token + "\"", pass)) return;
			entered = pass.getPassword();
			pass.setText("");
			if (entered.length == 0) return;
		}
		char[] passphrase = entered;
		long v = version;
		trigger.setEnabled(false);
		setNote("Checking your passphrase…", false);
		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() throws Exception {
				return host.revealTokenKey(token, passphrase);
			}

			@Override
			protected void done() {
				java.util.Arrays.fill(passphrase, '\0');
				trigger.setEnabled(true);
				if (version != v) return; // the list was rebuilt meanwhile
				try {
					use.accept(get());
				} catch (java.util.concurrent.ExecutionException ex) {
					String why = ex.getCause().getMessage();
					setNote((why == null) ? "Couldn't read the key." : why, true);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		}.execute();
	}

	/** Drops every key or login shown, and stops following the store, when the user logs out or switches. */
	public void clearSensitive() {
		if (watch != null) watch.close();
		watch = null;
		version++;
		tokenList.removeAll();
		tokenList.revalidate();
		tokenList.repaint();
		clearIssued();
		clearNote();
	}
}
