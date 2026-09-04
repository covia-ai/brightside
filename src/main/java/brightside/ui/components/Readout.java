package brightside.ui.components;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import javax.swing.text.TabSet;
import javax.swing.text.TabStop;

import com.formdev.flatlaf.util.UIScale;

/**
 * A read-only document for the inspectors: sections, entries, prose, code,
 * key/value pairs and notes, laid out as text in one {@link JTextPane}. The
 * text wraps at whatever width the pane is given and scrolls in
 * {@link Scrolls#vertical}, so a long list needs no layout of its own — and
 * because it is one document, everything shown (headings, metadata, values)
 * is selectable and copyable in a single sweep. Long prose or code goes in as
 * an {@linkplain #excerpt excerpt}, folded to a few lines behind a "Show all"
 * link.
 *
 * <p>Build it fluently, in reading order; the document is rendered when the
 * pane is first shown, and again in the theme's fresh faces and colours after
 * a look-and-feel change. Text is shown literally: much of it is written by an
 * agent or a model, and none of it is read as markup.
 */
@SuppressWarnings("serial")
public class Readout extends JTextPane {

	/** The fold: an excerpt shows this many lines, and this many characters. */
	public static final int EXCERPT_LINES = 6;
	public static final int EXCERPT_CHARS = 600;

	/** Character attribute on a fold link: the index of the excerpt it shows or hides. */
	private static final Object TOGGLE = new Object() {
		@Override
		public String toString() {
			return "readout.toggle";
		}
	};

	/** Unscaled pixels: the indent under an entry, the key column of a pair, the space between blocks. */
	private static final int INDENT = 22;
	private static final int KEY_WIDTH = 130;
	private static final int GAP = 8;

	private sealed interface Part permits Section, Entry, Block, Caption, Note, Pair {
	}

	private record Section(String title, String tone) implements Part {
	}

	private record Entry(String title, String tone, List<String> meta) implements Part {
	}

	/** Prose or code, indented under an entry, folded behind a link when clamped. */
	private record Block(String text, boolean mono, boolean indented, boolean clamped) implements Part {
	}

	private record Caption(String text, boolean indented) implements Part {
	}

	private record Note(String text) implements Part {
	}

	private record Pair(String key, String value, String tone) implements Part {
	}

	private final List<Part> parts = new ArrayList<>();
	/** The excerpts shown whole, by index in {@link #parts}. */
	private final Set<Integer> expanded = new HashSet<>();
	/** Whether the next block sits under an entry, and so indents. */
	private boolean underEntry;
	private boolean stale = true;
	private boolean built;

	public Readout() {
		setEditable(false);
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(12, 16, 16, 16));
		quietCaret();
		MouseAdapter folds = new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 1) return;
				if (getSelectionStart() != getSelectionEnd()) return; // a selection, not a click
				Integer index = toggleAt(e.getPoint());
				if (index == null) return;
				if (!expanded.remove(index)) expanded.add(index);
				render();
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				setCursor(Cursor.getPredefinedCursor(
					toggleAt(e.getPoint()) != null ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
			}
		};
		addMouseListener(folds);
		addMouseMotionListener(folds);
		built = true;
	}

	// ------------------------------------------------------------------
	// Building
	// ------------------------------------------------------------------

	/** A heading in the accent, introducing the entries that follow. */
	public Readout section(String title) {
		return section(title, Styles.ACCENT);
	}

	/** A heading in a tone — {@link Styles#ERROR}, {@link Styles#WARNING}, … */
	public Readout section(String title, String tone) {
		return add(new Section(title, tone), false);
	}

	/**
	 * An entry: a bold title (in {@code tone} when one is given) over small
	 * muted lines; blank lines are dropped. What follows is indented under it.
	 */
	public Readout entry(String title, String tone, String... meta) {
		List<String> lines = new ArrayList<>();
		for (String m : meta) {
			if (m != null && !m.isBlank()) lines.add(m);
		}
		return add(new Entry(title, tone, List.copyOf(lines)), true);
	}

	/** Wrapping prose, shown whole. */
	public Readout prose(String text) {
		return add(new Block(orEmpty(text), false, underEntry, false), underEntry);
	}

	/** Monospaced text — code, JSON — shown whole. */
	public Readout code(String text) {
		return add(new Block(orEmpty(text), true, underEntry, false), underEntry);
	}

	/** Prose or code folded to a few lines behind a "Show all" link when it is longer. */
	public Readout excerpt(String text, boolean mono) {
		return add(new Block(orEmpty(text), mono, underEntry, true), underEntry);
	}

	/** The smallest, muted line: a caption over a detail. */
	public Readout caption(String text) {
		return add(new Caption(text, underEntry), underEntry);
	}

	/** A small, muted, wrapping note across the width — a caveat, an empty state. */
	public Readout note(String text) {
		return add(new Note(text), false);
	}

	/** A key beside its value, keys aligned in a column of such rows. */
	public Readout pair(String key, String value) {
		return pair(key, value, null);
	}

	/** A key beside its value in a tone. */
	public Readout pair(String key, String value, String tone) {
		return add(new Pair(key, value, tone), false);
	}

	private Readout add(Part part, boolean underEntry) {
		parts.add(part);
		this.underEntry = underEntry;
		stale = true;
		if (isDisplayable()) render();
		return this;
	}

	private static String orEmpty(String s) {
		return (s != null) ? s : "";
	}

	// ------------------------------------------------------------------
	// Showing
	// ------------------------------------------------------------------

	/** Always as wide as its viewport: text wraps rather than widening the page. */
	@Override
	public boolean getScrollableTracksViewportWidth() {
		return true;
	}

	@Override
	public void addNotify() {
		super.addNotify();
		if (stale) render();
	}

	@Override
	public void updateUI() {
		super.updateUI();
		if (built) {
			quietCaret(); // a theme change installs a fresh caret
			render();
		}
	}

	/**
	 * Read-only text never follows an edit: rendering leaves the enclosing
	 * scroll pane where it was rather than scrolling to the caret.
	 */
	private void quietCaret() {
		if (getCaret() instanceof DefaultCaret caret) caret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
	}

	private void render() {
		stale = false;
		JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
		Point at = (viewport != null) ? viewport.getViewPosition() : null;
		setStyledDocument(new Writer().write());
		setCaretPosition(0);
		// A fold re-renders the whole document; the reader stays where they were.
		if (at != null) SwingUtilities.invokeLater(() -> viewport.setViewPosition(at));
	}

	/** The excerpt whose fold link is under {@code p}, or null when there is none. */
	private Integer toggleAt(Point p) {
		StyledDocument doc = getStyledDocument();
		int pos = viewToModel2D(p);
		if (pos < 0 || pos >= doc.getLength()) return null;
		Element run = doc.getCharacterElement(pos);
		Object index = run.getAttributes().getAttribute(TOGGLE);
		if (!(index instanceof Integer i)) return null;
		try {
			// The nearest position past a line's end is its last character:
			// only a pointer actually on the run counts.
			Rectangle2D at = modelToView2D(pos);
			if (at == null || p.getY() < at.getY() || p.getY() > at.getMaxY()) return null;
			Rectangle2D end = modelToView2D(Math.min(run.getEndOffset(), doc.getLength()));
			if (end != null && end.getY() == at.getY() && p.getX() > end.getX()) return null;
		} catch (BadLocationException e) {
			return null;
		}
		return i;
	}

	/**
	 * The leading part of {@code text} within {@code maxLines} lines and
	 * {@code maxChars} characters — cut at a space where one falls in the
	 * second half — or null when all of it already fits.
	 */
	static String clamp(String text, int maxLines, int maxChars) {
		String[] lines = text.split("\n", -1);
		boolean cutLines = lines.length > maxLines;
		String head = cutLines ? String.join("\n", Arrays.copyOf(lines, maxLines)) : text;
		if (head.length() > maxChars) {
			int space = head.lastIndexOf(' ', maxChars);
			head = head.substring(0, (space > maxChars / 2) ? space : maxChars);
			return head.stripTrailing();
		}
		return cutLines ? head.stripTrailing() : null;
	}

	private static Color colour(String tone, Color fallback) {
		if (tone == null) return fallback;
		return switch (tone) {
			case Styles.ERROR -> Theme.error();
			case Styles.WARNING -> Theme.warning();
			case Styles.SUCCESS -> Theme.success();
			case Styles.ACCENT -> Theme.accent();
			case Styles.MUTED -> Theme.muted();
			default -> fallback;
		};
	}

	// ------------------------------------------------------------------
	// Rendering: the parts into a fresh document, in the theme of the moment
	// ------------------------------------------------------------------

	private final class Writer {
		private final DefaultStyledDocument doc = new DefaultStyledDocument();
		private final Font body = face("Label.font", Font.DIALOG, UIScale.scale(13f));
		private final Font small = face("small.font", Font.DIALOG, body.getSize2D() - UIScale.scale(2f));
		private final Font mini = face("mini.font", Font.DIALOG, body.getSize2D() - UIScale.scale(3f));
		private final Font mono = face("monospaced.font", Font.MONOSPACED, small.getSize2D()).deriveFont(small.getSize2D());
		private final Color foreground = Theme.foreground();
		private final Color muted = Theme.muted();
		private final Color accent = Theme.accent();
		private final Color codeGround = Theme.codeBackground();
		private final int gap = UIScale.scale(GAP);
		private final int indent = UIScale.scale(INDENT);
		private final int keyWidth = UIScale.scale(KEY_WIDTH);
		/** The block just written was a caption, so the next sits close under it. */
		private boolean afterCaption;

		StyledDocument write() {
			for (int i = 0; i < parts.size(); i++) {
				switch (parts.get(i)) {
					case Section s -> section(s);
					case Entry e -> entry(e);
					case Block b -> block(b, i);
					case Caption c -> caption(c);
					case Note n -> note(n);
					case Pair p -> pair(p);
				}
			}
			return doc;
		}

		private void section(Section s) {
			int start = open();
			insert(s.title(), run(body, true, colour(s.tone(), accent)));
			close(start, 0, 0, gap * 2, null);
			afterCaption = false;
		}

		private void entry(Entry e) {
			int start = open();
			insert(e.title(), run(body, true, colour(e.tone(), foreground)));
			for (String m : e.meta()) insert("\n" + m, run(small, false, muted));
			close(start, 0, 0, gap * 1.5f, null);
			afterCaption = false;
		}

		private void block(Block b, int index) {
			String text = b.text();
			String shown = text;
			String fold = null;
			if (b.clamped()) {
				String clamped = clamp(text, EXCERPT_LINES, EXCERPT_CHARS);
				if (clamped != null) {
					boolean whole = expanded.contains(index);
					shown = whole ? text : clamped + " …";
					int lines = text.split("\n", -1).length;
					fold = whole ? "Show less"
						: (lines > 1) ? String.format("Show all  ·  %,d lines", lines)
						: String.format("Show all  ·  %,d characters", text.length());
				}
			}
			int left = b.indented() ? indent : 0;
			int start = open();
			insert(shown, b.mono() ? code() : run(body, false, foreground));
			close(start, left, 0, afterCaption ? gap / 4f : gap / 2f, null);
			if (fold != null) {
				int link = open();
				SimpleAttributeSet a = run(small, false, accent);
				a.addAttribute(TOGGLE, index);
				insert(fold, a);
				close(link, left, 0, gap / 4f, null);
			}
			afterCaption = false;
		}

		private void caption(Caption c) {
			int start = open();
			insert(c.text(), run(mini, false, muted));
			close(start, c.indented() ? indent : 0, 0, gap, null);
			afterCaption = true;
		}

		private void note(Note n) {
			int start = open();
			insert(n.text(), run(small, false, muted));
			close(start, 0, 0, gap * 1.5f, null);
			afterCaption = false;
		}

		/** The key hangs in the column to the left of the indent; a tab takes the value to the indent, where its wrapped lines continue. */
		private void pair(Pair p) {
			int start = open();
			insert(p.key() + "\t", run(body, false, muted));
			insert(p.value(), run(body, false, colour(p.tone(), foreground)));
			close(start, keyWidth, keyWidth, gap / 2f, new TabSet(new TabStop[] {new TabStop(0)}));
			afterCaption = false;
		}

		/** Starts a block: a line break after whatever came before. Returns where it starts. */
		private int open() {
			if (doc.getLength() > 0) insert("\n", run(body, false, foreground));
			return doc.getLength();
		}

		/**
		 * Lays out the lines written since the block opened: every line shares
		 * the indent (and the tabs); only the first carries the space above and
		 * the hanging first-line indent.
		 */
		private void close(int start, float left, float hang, float above, TabSet tabs) {
			int length = Math.max(0, doc.getLength() - start);
			SimpleAttributeSet all = new SimpleAttributeSet();
			StyleConstants.setLeftIndent(all, left);
			StyleConstants.setFirstLineIndent(all, 0);
			StyleConstants.setSpaceAbove(all, 0);
			StyleConstants.setSpaceBelow(all, 0);
			if (tabs != null) StyleConstants.setTabSet(all, tabs);
			doc.setParagraphAttributes(start, length, all, false);

			SimpleAttributeSet first = new SimpleAttributeSet();
			StyleConstants.setFirstLineIndent(first, -hang);
			StyleConstants.setSpaceAbove(first, (start > 0) ? above : 0);
			doc.setParagraphAttributes(start, 1, first, false);
		}

		private void insert(String text, SimpleAttributeSet attributes) {
			if (text == null || text.isEmpty()) return;
			try {
				doc.insertString(doc.getLength(), text, attributes);
			} catch (BadLocationException e) {
				throw new IllegalStateException("appending cannot be out of bounds", e);
			}
		}

		private SimpleAttributeSet run(Font f, boolean bold, Color c) {
			SimpleAttributeSet a = new SimpleAttributeSet();
			StyleConstants.setFontFamily(a, f.getFamily());
			StyleConstants.setFontSize(a, Math.round(f.getSize2D()));
			StyleConstants.setBold(a, bold || f.isBold());
			StyleConstants.setItalic(a, f.isItalic());
			StyleConstants.setForeground(a, c);
			return a;
		}

		/** The monospaced face on the code ground. */
		private SimpleAttributeSet code() {
			SimpleAttributeSet a = run(mono, false, foreground);
			StyleConstants.setBackground(a, codeGround);
			return a;
		}

		/** The theme's font under {@code key}, so it tracks the default font and its scaling. */
		private static Font face(String key, String fallbackName, float fallbackSize) {
			Font f = UIManager.getFont(key);
			return (f != null) ? f : new Font(fallbackName, Font.PLAIN, Math.round(fallbackSize));
		}
	}
}
