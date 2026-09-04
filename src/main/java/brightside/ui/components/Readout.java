package brightside.ui.components;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.JTextPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;
import javax.swing.text.DefaultHighlighter;
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
 * lists, key/value pairs and notes, laid out as text in one {@link JTextPane}.
 * The text wraps at whatever width the pane is given and scrolls in
 * {@link Scrolls#vertical}, so a long list needs no layout of its own — and
 * because it is one document, everything shown (headings, metadata, values)
 * is selectable and copyable in a single sweep. Long prose or code goes in as
 * an {@linkplain #excerpt excerpt}, folded to a few lines behind a "Show all"
 * link.
 *
 * <p>Text can trigger things: a {@link Link} — in an entry's metadata, as a
 * pair's value, in a {@linkplain #lines list} or on a {@linkplain #link line
 * of its own} — runs its action on a click and shows a hand cursor. A block
 * {@linkplain #anchor named} beforehand can be brought into view with
 * {@link #scrollTo}, which is how a link navigates to detail elsewhere.
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

	/** Clickable text: {@code text} in the accent, running {@code action} on a click. */
	public record Link(String text, Runnable action) {
		public Link {
			Objects.requireNonNull(text, "text");
			Objects.requireNonNull(action, "action");
		}
	}

	/** Character attribute on clickable text: its {@link Runnable}. */
	private static final Object ACTION = new Object() {
		@Override
		public String toString() {
			return "readout.action";
		}
	};

	/** Unscaled pixels: the indent under an entry, the key column of a pair, the space between blocks. */
	private static final int INDENT = 22;
	private static final int KEY_WIDTH = 130;
	private static final int GAP = 8;

	private sealed interface Part permits Section, Entry, Block, Lines, LinkLine, Caption, Note, Pair {
	}

	private record Section(String title, String tone) implements Part {
	}

	/** {@code meta}: strings and links, one per line under the title. */
	private record Entry(String title, String tone, List<Object> meta) implements Part {
	}

	/** Prose or code, indented under an entry, folded behind a link when clamped. */
	private record Block(String text, boolean mono, boolean indented, boolean clamped) implements Part {
	}

	/** Strings and links, one per line, folded past the excerpt's line count. */
	private record Lines(List<Object> items, boolean mono, boolean indented) implements Part {
	}

	private record LinkLine(Link link, boolean indented) implements Part {
	}

	private record Caption(String text, boolean indented) implements Part {
	}

	private record Note(String text) implements Part {
	}

	/** {@code value}: a string or a link. */
	private record Pair(String key, Object value, String tone) implements Part {
	}

	private final List<Part> parts = new ArrayList<>();
	/** The folds shown whole, by index in {@link #parts}. */
	private final Set<Integer> expanded = new HashSet<>();
	/** Anchor id → the index in {@link #parts} of the block it names. */
	private final Map<String, Integer> anchors = new HashMap<>();
	/** Where each part starts in the rendered document, and where its first line ends. */
	private int[] starts = new int[0];
	private int[] firstLineEnds = new int[0];
	/** The highlight {@link #scrollTo} leaves on the block it reached. */
	private Object mark;
	private String pendingAnchor;
	/** Whether the next block sits under an entry, and so indents. */
	private boolean underEntry;
	private boolean stale = true;
	private boolean built;

	public Readout() {
		setEditable(false);
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(12, 16, 16, 16));
		quietCaret();
		MouseAdapter clicks = new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (!SwingUtilities.isLeftMouseButton(e) || e.getClickCount() != 1) return;
				if (getSelectionStart() != getSelectionEnd()) return; // a selection, not a click
				Runnable action = actionAt(e.getPoint());
				if (action != null) action.run();
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				setCursor(Cursor.getPredefinedCursor(
					actionAt(e.getPoint()) != null ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
			}
		};
		addMouseListener(clicks);
		addMouseMotionListener(clicks);
		built = true;
	}

	// ------------------------------------------------------------------
	// Building
	// ------------------------------------------------------------------

	/** Names the next block, so {@link #scrollTo} can bring it into view. */
	public Readout anchor(String id) {
		pendingAnchor = id;
		return this;
	}

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
	 * muted lines — each a string or a {@link Link}; blank ones are dropped.
	 * What follows is indented under it.
	 */
	public Readout entry(String title, String tone, Object... meta) {
		return add(new Entry(title, tone, textsAndLinks(meta)), true);
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

	/**
	 * A list, one item per line — each a string or a {@link Link} — in the
	 * monospaced face when {@code mono}, folded like an excerpt when long.
	 */
	public Readout lines(boolean mono, List<?> items) {
		return add(new Lines(textsAndLinks(items.toArray()), mono, underEntry), underEntry);
	}

	/** A small clickable line of its own. */
	public Readout link(String text, Runnable action) {
		return add(new LinkLine(new Link(text, action), underEntry), underEntry);
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
		return add(new Pair(key, orEmpty(value), tone), false);
	}

	/** A key beside a clickable value. */
	public Readout pair(String key, Link value) {
		return add(new Pair(key, Objects.requireNonNull(value, "value"), null), false);
	}

	private Readout add(Part part, boolean underEntry) {
		if (pendingAnchor != null) {
			anchors.put(pendingAnchor, parts.size());
			pendingAnchor = null;
		}
		parts.add(part);
		this.underEntry = underEntry;
		stale = true;
		if (isDisplayable()) render();
		return this;
	}

	/** Strings and links, blanks and nulls dropped; anything else is a programming error. */
	private static List<Object> textsAndLinks(Object... items) {
		List<Object> out = new ArrayList<>();
		for (Object item : items) {
			if (item == null) continue;
			if (item instanceof String s) {
				if (!s.isBlank()) out.add(s);
			} else if (item instanceof Link l) {
				out.add(l);
			} else {
				throw new IllegalArgumentException("a String or a Readout.Link, not " + item.getClass().getName());
			}
		}
		return List.copyOf(out);
	}

	private static String orEmpty(String s) {
		return (s != null) ? s : "";
	}

	// ------------------------------------------------------------------
	// Showing
	// ------------------------------------------------------------------

	/**
	 * Brings the block anchored {@code id} into view — at the top where the
	 * document allows — and highlights its first line. False when nothing is
	 * anchored so, or the pane has not been laid out yet.
	 */
	public boolean scrollTo(String id) {
		Integer index = anchors.get(id);
		if (index == null || index >= starts.length) return false;
		int start = starts[index];
		try {
			Rectangle2D at = modelToView2D(start);
			if (at == null) return false;
			JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, this);
			int height = (viewport != null) ? viewport.getExtentSize().height : (int) at.getHeight();
			scrollRectToVisible(new Rectangle(0, (int) at.getY() - UIScale.scale(GAP), 1, height));
			if (mark != null) getHighlighter().removeHighlight(mark);
			mark = getHighlighter().addHighlight(start, firstLineEnds[index],
				new DefaultHighlighter.DefaultHighlightPainter(Theme.fade(Theme.accent(), 0.3f)));
		} catch (BadLocationException e) {
			return false;
		}
		return true;
	}

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
		if (mark != null) {
			getHighlighter().removeHighlight(mark);
			mark = null;
		}
		setStyledDocument(new Writer().write());
		setCaretPosition(0);
		// A fold re-renders the whole document; the reader stays where they were.
		if (at != null) SwingUtilities.invokeLater(() -> viewport.setViewPosition(at));
	}

	private void toggle(int index) {
		if (!expanded.remove(index)) expanded.add(index);
		render();
	}

	/** The action of the clickable text under {@code p}, or null when there is none. */
	private Runnable actionAt(Point p) {
		StyledDocument doc = getStyledDocument();
		int pos = viewToModel2D(p);
		if (pos < 0 || pos >= doc.getLength()) return null;
		Element run = doc.getCharacterElement(pos);
		Object action = run.getAttributes().getAttribute(ACTION);
		if (!(action instanceof Runnable r)) return null;
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
		return r;
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
			int n = parts.size();
			int[] from = new int[n];
			int[] lineEnd = new int[n];
			for (int i = 0; i < n; i++) {
				// open() puts one line break before every block but the first.
				from[i] = (doc.getLength() > 0) ? doc.getLength() + 1 : 0;
				switch (parts.get(i)) {
					case Section s -> section(s);
					case Entry e -> entry(e);
					case Block b -> block(b, i);
					case Lines l -> lines(l, i);
					case LinkLine l -> linkLine(l);
					case Caption c -> caption(c);
					case Note x -> note(x);
					case Pair p -> pair(p);
				}
				lineEnd[i] = firstLineEnd(from[i]);
			}
			starts = from;
			firstLineEnds = lineEnd;
			return doc;
		}

		private int firstLineEnd(int from) {
			try {
				String rest = doc.getText(from, doc.getLength() - from);
				int nl = rest.indexOf('\n');
				return (nl >= 0) ? from + nl : doc.getLength();
			} catch (BadLocationException e) {
				return doc.getLength();
			}
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
			for (Object m : e.meta()) {
				insert("\n", run(small, false, muted));
				item(m, small, false);
			}
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
			if (fold != null) fold(fold, index, left);
			afterCaption = false;
		}

		private void lines(Lines l, int index) {
			List<Object> items = l.items();
			String fold = null;
			if (items.size() > EXCERPT_LINES) {
				boolean whole = expanded.contains(index);
				if (!whole) items = items.subList(0, EXCERPT_LINES);
				fold = whole ? "Show less" : String.format("Show all  ·  %,d lines", l.items().size());
			}
			int left = l.indented() ? indent : 0;
			int start = open();
			boolean first = true;
			for (Object item : items) {
				if (!first) insert("\n", l.mono() ? code() : run(body, false, foreground));
				first = false;
				item(item, l.mono() ? mono : body, l.mono());
			}
			close(start, left, 0, afterCaption ? gap / 4f : gap / 2f, null);
			if (fold != null) fold(fold, index, left);
			afterCaption = false;
		}

		private void linkLine(LinkLine l) {
			int start = open();
			insert(l.link().text(), link(l.link(), small, false));
			close(start, l.indented() ? indent : 0, 0, afterCaption ? gap / 4f : gap / 2f, null);
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
			if (p.value() instanceof Link l) insert(l.text(), link(l, body, false));
			else insert((String) p.value(), run(body, false, colour(p.tone(), foreground)));
			close(start, keyWidth, keyWidth, gap / 2f, new TabSet(new TabStop[] {new TabStop(0)}));
			afterCaption = false;
		}

		/** A string as small muted text, or a link, in {@code font}; on the code ground when {@code mono}. */
		private void item(Object item, Font font, boolean mono) {
			if (item instanceof Link l) {
				insert(l.text(), link(l, font, mono));
			} else {
				SimpleAttributeSet a = mono ? code() : run(font, false, muted);
				insert((String) item, a);
			}
		}

		/** The "Show all" / "Show less" line under a folded block. */
		private void fold(String text, int index, int left) {
			int start = open();
			insert(text, link(new Link(text, () -> toggle(index)), small, false));
			close(start, left, 0, gap / 4f, null);
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

		/** Clickable text: the accent, carrying its action. */
		private SimpleAttributeSet link(Link l, Font font, boolean mono) {
			SimpleAttributeSet a = mono ? code() : run(font, false, accent);
			StyleConstants.setForeground(a, accent);
			a.addAttribute(ACTION, l.action());
			return a;
		}

		/** The theme's font under {@code key}, so it tracks the default font and its scaling. */
		private static Font face(String key, String fallbackName, float fallbackSize) {
			Font f = UIManager.getFont(key);
			return (f != null) ? f : new Font(fallbackName, Font.PLAIN, Math.round(fallbackSize));
		}
	}
}
