package brightside.voice;

import java.util.ArrayList;
import java.util.List;

import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;

/**
 * What of an assistant's Markdown reply is read aloud: its prose, as a listener
 * would hear it. Code blocks, tables, images and raw HTML are left out; a link
 * is read by its text, never its address; inline code is read as its words.
 * Headings, paragraphs and list items each end a sentence. The words come in
 * {@linkplain #parts parts} small enough for the voice to start soon and well
 * within what one speech request takes.
 */
public final class SpokenText {

	/** The longest part said at once, in characters; the venue takes 4,096. */
	static final int PART = 600;

	private static final Parser PARSER = Parser.builder().extensions(List.of(TablesExtension.create())).build();

	private SpokenText() {
	}

	/** The reply's spoken words, block by block; empty when nothing in it is for reading aloud. */
	public static List<String> blocks(String markdown) {
		Reader reader = new Reader();
		PARSER.parse(markdown == null ? "" : markdown).accept(reader);
		reader.end();
		return reader.blocks;
	}

	/** The reply's spoken words in parts of at most {@link #PART} characters, split between sentences where it can be. */
	public static List<String> parts(String markdown) {
		List<String> parts = new ArrayList<>();
		StringBuilder part = new StringBuilder();
		for (String block : blocks(markdown)) {
			for (String piece : pieces(block)) {
				if (!part.isEmpty() && part.length() + 1 + piece.length() > PART) {
					parts.add(part.toString());
					part.setLength(0);
				}
				if (!part.isEmpty()) part.append(' ');
				part.append(piece);
			}
		}
		if (!part.isEmpty()) parts.add(part.toString());
		return parts;
	}

	/** A block as sentences, and any sentence longer than a part as runs of whole words. */
	private static List<String> pieces(String block) {
		List<String> out = new ArrayList<>();
		for (String sentence : block.split("(?<=[.!?…])\\s+")) {
			if (sentence.length() <= PART) {
				out.add(sentence);
				continue;
			}
			StringBuilder run = new StringBuilder();
			for (String word : sentence.split("\\s+")) {
				if (!run.isEmpty() && run.length() + 1 + word.length() > PART) {
					out.add(run.toString());
					run.setLength(0);
				}
				if (!run.isEmpty()) run.append(' ');
				// A "word" longer than a part (a long address, say) is cut where it must be.
				while (word.length() > PART) {
					out.add(word.substring(0, PART));
					word = word.substring(PART);
				}
				run.append(word);
			}
			if (!run.isEmpty()) out.add(run.toString());
		}
		return out;
	}

	private static final class Reader extends AbstractVisitor {
		final List<String> blocks = new ArrayList<>();
		final StringBuilder block = new StringBuilder();

		/** What has been read since the last block ended, as a block of its own. */
		void end() {
			String said = block.toString().replaceAll("\\s+", " ").strip();
			block.setLength(0);
			if (said.isEmpty()) return;
			// A heading or list item has no stop of its own; give it one, so it is said as a sentence.
			if (!said.matches(".*[.!?…:;]$")) said += ".";
			blocks.add(said);
		}

		@Override public void visit(Text text) { block.append(text.getLiteral()); }
		@Override public void visit(Code code) { block.append(code.getLiteral()); }
		@Override public void visit(SoftLineBreak br) { block.append(' '); }
		@Override public void visit(HardLineBreak br) { block.append(' '); }

		@Override public void visit(Paragraph p) { visitChildren(p); end(); }
		@Override public void visit(Heading h) { end(); visitChildren(h); end(); }
		@Override public void visit(ListItem item) { end(); visitChildren(item); end(); }
		@Override public void visit(BlockQuote quote) { visitChildren(quote); end(); }

		// Not for reading aloud.
		@Override public void visit(FencedCodeBlock code) { end(); }
		@Override public void visit(IndentedCodeBlock code) { end(); }
		@Override public void visit(HtmlBlock html) { end(); }
		@Override public void visit(HtmlInline html) { }
		@Override public void visit(Image image) { }
		@Override public void visit(ThematicBreak br) { end(); }
		@Override public void visit(CustomBlock custom) {
			if (custom instanceof TableBlock) end();
			else visitChildren(custom);
		}

		@Override protected void visitChildren(Node parent) {
			for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) n.accept(this);
		}
	}
}
