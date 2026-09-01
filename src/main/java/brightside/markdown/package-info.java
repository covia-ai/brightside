/**
 * Markdown rendering for Swing: commonmark-java's AST transformed into a
 * {@link javax.swing.text.StyledDocument}, shown in a
 * {@link brightside.markdown.MarkdownPane}.
 *
 * <p>Self-contained on purpose. This package depends on commonmark-java and the
 * JDK only — never on the rest of Brightside — so it can be lifted out as a
 * library as it stands. A host describes how rendered text should look with a
 * {@link brightside.markdown.MarkdownStyle} built from its own theme (and
 * rebuilt when the theme changes); everything else is the package's business.
 *
 * <p>The transform is deliberately simple: one document, paragraph attributes
 * for structure (indent, spacing, hanging list markers), character attributes
 * for inline style, and a link's destination carried as the character attribute
 * {@link brightside.markdown.MarkdownRenderer#LINK}. Tables are laid out in the
 * monospaced face; there are no custom views.
 */
package brightside.markdown;
