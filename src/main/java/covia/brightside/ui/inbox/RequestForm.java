package covia.brightside.ui.inbox;

import static covia.brightside.ui.inspect.Blocks.body;
import static covia.brightside.ui.inspect.Blocks.divider;
import static covia.brightside.ui.inspect.Blocks.errorColor;
import static covia.brightside.ui.inspect.Blocks.heading;
import static covia.brightside.ui.inspect.Blocks.muted;
import static covia.brightside.ui.inspect.Blocks.small;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;

import covia.brightside.Inbox;
import covia.brightside.ui.LAF;

/**
 * One request in full: who is asking, the description, every ask with the
 * control to answer it, and the offered grants as explicit consent boxes that
 * only come alive when the choice that triggers them is made. A resolved
 * request shows its response instead. Titles, descriptions and prompts are
 * agent-written: rendered as plain text, never as markup.
 *
 * <p>Dumb: it collects an {@link Inbox.Answer} and reports through the
 * {@link Listener}; the app performs the operation.
 */
@SuppressWarnings("serial")
public final class RequestForm extends JPanel {

	/** The owner's decision; the app resolves it through {@code hitl:respond}. */
	public interface Listener {
		void onAnswer(String id, Inbox.Answer answer);

		/** {@code reason} may be empty. */
		void onReject(String id, String reason);
	}

	private final Inbox.Request request;
	private final Listener listener;
	private final LinkedHashMap<String, AskInput> inputs = new LinkedHashMap<>();
	private final JLabel status = small(" ");
	final JTextArea comment = textArea(2);
	final JButton answerButton = new JButton("Answer");
	final JButton rejectButton = new JButton("Reject…");

	public RequestForm(Inbox.Request request, Listener listener) {
		this.request = request;
		this.listener = listener;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));

		JTextArea title = body(request.title(), false);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		add(title);
		add(small(meta(request)));
		if (request.description() != null && !request.description().isBlank()) {
			add(gap(8));
			add(body(request.description(), false));
		}
		add(gap(6));
		add(divider());

		for (Inbox.Ask ask : request.asks()) {
			AskInput input = switch (ask.type() == null ? "" : ask.type()) {
				case "text" -> new TextInput(ask);
				case "approval" -> new ApprovalInput(ask);
				case "choice" -> new ChoiceInput(ask);
				case "checkboxes" -> new CheckboxesInput(ask);
				default -> new TokenInput(ask);
			};
			inputs.put(ask.id(), input);
			add(gap(10));
			add(input);
		}

		add(gap(10));
		add(divider());
		if (request.open()) {
			add(gap(6));
			add(small("Comment (optional)"));
			add(comment);
			add(gap(8));
			answerButton.setBackground(LAF.ACCENT);
			answerButton.setForeground(Color.WHITE);
			answerButton.addActionListener(e -> {
				Inbox.Answer answer = collect();
				if (answer == null) return;
				setBusy(true);
				listener.onAnswer(request.id(), answer);
			});
			rejectButton.addActionListener(e -> {
				Object reason = JOptionPane.showInputDialog(this, "Reason (optional):", "Reject request",
					JOptionPane.PLAIN_MESSAGE, null, null, "");
				if (reason == null) return;
				setBusy(true);
				listener.onReject(request.id(), reason.toString().trim());
			});
			JPanel buttons = new JPanel();
			buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
			buttons.setOpaque(false);
			buttons.setAlignmentX(LEFT_ALIGNMENT);
			buttons.add(answerButton);
			buttons.add(Box.createHorizontalStrut(8));
			buttons.add(rejectButton);
			add(buttons);
			add(gap(4));
			add(status);
			for (AskInput in : inputs.values()) in.setEnabled(true);
		} else {
			add(gap(6));
			add(response(request));
			for (AskInput in : inputs.values()) in.setEnabled(false);
		}
		refreshGrants();
	}

	public Inbox.Request request() {
		return request;
	}

	/** Buttons off while the response is in flight; back on if it failed. */
	void setBusy(boolean busy) {
		answerButton.setEnabled(!busy);
		rejectButton.setEnabled(!busy);
		status.setForeground(busy ? muted() : errorColor());
		if (busy) status.setText("Sending…");
	}

	/** The answer as entered, or null (with the reason shown) if a required ask is unanswered. */
	Inbox.Answer collect() {
		Map<String, Object> answers = new LinkedHashMap<>();
		Map<String, String> comments = new LinkedHashMap<>();
		List<Inbox.Grant> echoes = new ArrayList<>();
		for (AskInput in : inputs.values()) {
			Object value = in.value();
			if (value == null) {
				if (in.ask.required()) {
					fail(in instanceof TokenInput
						? "\"" + in.ask.prompt() + "\" needs a token signed with your own key, which Brightside "
							+ "can't provide yet — reject the request instead."
						: "Answer the required questions first.");
					return null;
				}
				continue;
			}
			answers.put(in.ask.id(), value);
			echoes.addAll(in.echoes());
			if (in.comment != null && !in.comment.getText().isBlank()) comments.put(in.ask.id(), in.comment.getText().trim());
		}
		status.setText(" ");
		return new Inbox.Answer(answers, comments, echoes, comment.getText());
	}

	AskInput input(String askId) {
		return inputs.get(askId);
	}

	private void fail(String message) {
		status.setForeground(errorColor());
		status.setText(message);
	}

	private void refreshGrants() {
		for (AskInput in : inputs.values()) in.refreshGrants();
	}

	private static JPanel response(Inbox.Request r) {
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(LEFT_ALIGNMENT);
		p.add(heading(outcomeLabel(r)));
		Inbox.Response resp = r.response();
		if (resp != null) {
			for (Inbox.Ask ask : r.asks()) {
				String a = resp.answers().get(ask.id());
				if (a != null) p.add(body(ask.prompt() + "  →  " + a, false));
			}
			if (resp.comment() != null) p.add(body("Comment: " + resp.comment(), false));
			for (Inbox.Grant g : resp.grants()) p.add(body("Granted " + grantLabel(g), false));
		}
		return p;
	}

	private static String outcomeLabel(Inbox.Request r) {
		return switch (r.status() == null ? "" : r.status()) {
			case "answered" -> "Answered";
			case "rejected" -> "Rejected";
			case "expired" -> "Expired unanswered";
			case "cancelled" -> "Withdrawn by the requester";
			default -> r.status();
		};
	}

	/** "Asked by Bob · 27 Aug 2026, 14:02 · expires in 2 h". */
	static String meta(Inbox.Request r) {
		StringBuilder sb = new StringBuilder("Asked by ");
		sb.append((r.agent() != null) ? r.agent() : (r.from() != null) ? r.from() : "unknown");
		sb.append("  ·  ").append(when(r.created()));
		if (r.open() && r.expires() > 0) sb.append("  ·  ").append(expiry(r.expires(), System.currentTimeMillis()));
		return sb.toString();
	}

	static String expiry(long expires, long now) {
		long left = expires - now;
		if (left <= 0) return "expiring now";
		if (left < 60_000L) return "expires in under a minute";
		if (left < 3_600_000L) return "expires in " + (left / 60_000L) + " min";
		if (left < 86_400_000L) return "expires in " + (left / 3_600_000L) + " h";
		return "expires " + when(expires);
	}

	static String when(long ms) {
		return (ms <= 0) ? "—" : new SimpleDateFormat("d MMM yyyy, HH:mm").format(new Date(ms));
	}

	static String grantLabel(Inbox.Grant g) {
		String s = g.can() + " on " + g.with();
		return (g.exp() != null) ? s + " until " + when(g.exp() * 1000) : s;
	}

	private static Component gap(int h) {
		return Box.createVerticalStrut(h);
	}

	private static JTextArea textArea(int rows) {
		JTextArea ta = new JTextArea(rows, 30);
		ta.setLineWrap(true);
		ta.setWrapStyleWord(true);
		ta.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(line()), BorderFactory.createEmptyBorder(4, 6, 4, 6)));
		ta.setAlignmentX(LEFT_ALIGNMENT);
		ta.setMaximumSize(new Dimension(Integer.MAX_VALUE, ta.getPreferredSize().height + 8));
		return ta;
	}

	private static Color line() {
		Color c = UIManager.getColor("Separator.foreground");
		return (c != null) ? c : Color.GRAY;
	}

	// ------------------------------------------------------------------
	// Ask inputs
	// ------------------------------------------------------------------

	/** One echo-consent box: the grant it stands for, and whether the answer currently triggers it. */
	record GrantBox(JCheckBox box, Inbox.Grant grant, BooleanSupplier live) {
	}

	/** An ask's prompt and control(s); {@link #value()} is null while unanswered. */
	abstract static class AskInput extends JPanel {
		final Inbox.Ask ask;
		final List<GrantBox> grantBoxes = new ArrayList<>();
		final JTextField comment;

		AskInput(Inbox.Ask ask) {
			this.ask = ask;
			setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
			setOpaque(false);
			setAlignmentX(LEFT_ALIGNMENT);
			JTextArea prompt = body(ask.prompt() + (ask.required() ? "  *" : ""), false);
			prompt.setFont(prompt.getFont().deriveFont(Font.BOLD));
			add(prompt);
			comment = ask.allowComment() ? new JTextField(30) : null;
		}

		/** Called by subclasses once their controls are added: the consent boxes and comment go last. */
		void finish() {
			for (GrantBox g : grantBoxes) add(g.box());
			if (comment != null) {
				comment.putClientProperty("JTextField.placeholderText", "Comment (optional)");
				comment.setAlignmentX(LEFT_ALIGNMENT);
				comment.setMaximumSize(new Dimension(420, comment.getPreferredSize().height));
				add(Box.createVerticalStrut(4));
				add(comment);
			}
		}

		/** The answer value (Boolean / String / List of option ids), or null while unanswered. */
		abstract Object value();

		/** Offers grants as consent boxes, live only while {@code trigger} holds. */
		void offer(List<Inbox.Grant> grants, BooleanSupplier trigger) {
			for (Inbox.Grant g : grants) {
				JCheckBox box = new JCheckBox("Also grant " + grantLabel(g));
				box.setOpaque(false);
				box.setAlignmentX(LEFT_ALIGNMENT);
				box.setToolTipText("Conferred only if you tick this and make the choice that offers it");
				grantBoxes.add(new GrantBox(box, g, trigger));
			}
		}

		/** A consent box is enabled only while its choice is made; an unmade choice unticks it. */
		void refreshGrants() {
			for (GrantBox g : grantBoxes) {
				boolean live = isEnabled() && g.live().getAsBoolean();
				g.box().setEnabled(live);
				if (!live) g.box().setSelected(false);
			}
		}

		/** The grants the owner ticked, among those the answer triggers. */
		List<Inbox.Grant> echoes() {
			List<Inbox.Grant> out = new ArrayList<>();
			for (GrantBox g : grantBoxes) if (g.box().isEnabled() && g.box().isSelected()) out.add(g.grant());
			return out;
		}

		@Override
		public void setEnabled(boolean enabled) {
			super.setEnabled(enabled);
			for (Component c : getComponents()) c.setEnabled(enabled);
			if (comment != null) comment.setEnabled(enabled);
		}

		void onChange() {
			refreshGrants();
		}
	}

	static final class TextInput extends AskInput {
		final JTextArea text = textArea(3);

		TextInput(Inbox.Ask ask) {
			super(ask);
			add(text);
			finish();
		}

		@Override
		Object value() {
			String s = text.getText().trim();
			return s.isEmpty() ? null : s;
		}
	}

	static final class ApprovalInput extends AskInput {
		final JRadioButton yes = radio("Approve");
		final JRadioButton no = radio("Decline");

		ApprovalInput(Inbox.Ask ask) {
			super(ask);
			ButtonGroup group = new ButtonGroup();
			group.add(yes);
			group.add(no);
			yes.addItemListener(e -> onChange());
			no.addItemListener(e -> onChange());
			add(yes);
			add(no);
			offer(ask.grants(), yes::isSelected);
			finish();
		}

		@Override
		Object value() {
			return yes.isSelected() ? Boolean.TRUE : no.isSelected() ? Boolean.FALSE : null;
		}
	}

	static final class ChoiceInput extends AskInput {
		final Map<String, JRadioButton> buttons = new LinkedHashMap<>();

		ChoiceInput(Inbox.Ask ask) {
			super(ask);
			ButtonGroup group = new ButtonGroup();
			for (Inbox.Option o : ask.options()) {
				JRadioButton b = radio(o.label());
				b.addItemListener(e -> onChange());
				group.add(b);
				buttons.put(o.id(), b);
				add(b);
				offer(o.grants(), b::isSelected);
			}
			finish();
		}

		@Override
		Object value() {
			for (Map.Entry<String, JRadioButton> e : buttons.entrySet()) if (e.getValue().isSelected()) return e.getKey();
			return null;
		}
	}

	static final class CheckboxesInput extends AskInput {
		final Map<String, JCheckBox> boxes = new LinkedHashMap<>();

		CheckboxesInput(Inbox.Ask ask) {
			super(ask);
			for (Inbox.Option o : ask.options()) {
				JCheckBox b = new JCheckBox(o.label());
				b.setOpaque(false);
				b.setAlignmentX(LEFT_ALIGNMENT);
				b.addItemListener(e -> onChange());
				boxes.put(o.id(), b);
				add(b);
				offer(o.grants(), b::isSelected);
			}
			finish();
		}

		@Override
		Object value() {
			List<String> selected = new ArrayList<>();
			for (Map.Entry<String, JCheckBox> e : boxes.entrySet()) if (e.getValue().isSelected()) selected.add(e.getKey());
			return selected.isEmpty() ? null : selected;
		}
	}

	/** A token ask wants a UCAN signed with the owner's own key — nothing here can sign one yet. */
	static final class TokenInput extends AskInput {

		TokenInput(Inbox.Ask ask) {
			super(ask);
			for (Inbox.Grant cap : ask.tokenCaps()) add(body("Wants " + grantLabel(cap), false));
			JLabel note = small("Needs a token signed with your own key; Brightside can't sign one yet.");
			note.setForeground(errorColor());
			add(note);
			finish();
		}

		@Override
		Object value() {
			return null;
		}
	}

	private static JRadioButton radio(String label) {
		JRadioButton b = new JRadioButton(label);
		b.setOpaque(false);
		b.setAlignmentX(LEFT_ALIGNMENT);
		return b;
	}

}
