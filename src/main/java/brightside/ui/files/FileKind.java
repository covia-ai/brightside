package brightside.ui.files;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What a file is, read from its extension: the {@link brightside.ui.components.Lucide}
 * icon its row and preview show, and a word for it. Nothing is opened to
 * decide; an extension the table doesn't know is just a file.
 */
enum FileKind {

	FOLDER("folder", "Folder"),
	CODE("file-code", "Code", "java", "kt", "kts", "scala", "groovy", "gradle", "clj", "cljs", "cljc", "cvx", "lisp",
		"py", "rb", "go", "rs", "c", "h", "cc", "cpp", "hpp", "cs", "swift", "m", "js", "mjs", "cjs", "ts", "tsx", "jsx",
		"vue", "svelte", "html", "htm", "css", "scss", "less", "sh", "bash", "zsh", "ps1", "bat", "cmd", "sql", "php",
		"pl", "lua", "r", "dart"),
	DATA("file-braces", "Data", "json", "json5", "jsonl", "yaml", "yml", "toml", "xml", "edn", "ini", "cfg", "conf",
		"properties", "env", "cad3"),
	TEXT("file-text", "Text", "txt", "md", "markdown", "log", "rst", "adoc", "tex"),
	DOCUMENT("file-text", "Document", "pdf", "doc", "docx", "odt", "rtf", "pages", "epub"),
	SPREADSHEET("file-spreadsheet", "Spreadsheet", "csv", "tsv", "xls", "xlsx", "ods", "numbers"),
	IMAGE("file-image", "Image", "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "ico", "tif", "tiff", "heic", "avif"),
	AUDIO("file-music", "Audio", "mp3", "wav", "flac", "ogg", "oga", "m4a", "aac", "opus"),
	VIDEO("file-video-camera", "Video", "mp4", "m4v", "mov", "mkv", "webm", "avi", "wmv"),
	ARCHIVE("file-archive", "Archive", "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "jar", "war", "zst"),
	OTHER("file", "File");

	private static final Map<String, FileKind> BY_EXTENSION = new HashMap<>();
	static {
		for (FileKind k : values()) for (String e : k.extensions) BY_EXTENSION.put(e, k);
	}

	final String icon;
	final String word;
	private final String[] extensions;

	FileKind(String icon, String word, String... extensions) {
		this.icon = icon;
		this.word = word;
		this.extensions = extensions;
	}

	/** The kind of {@code name}: a folder, else by the extension after its last dot (a dotfile has none). */
	static FileKind of(String name, boolean directory) {
		if (directory) return FOLDER;
		int dot = name.lastIndexOf('.');
		if (dot <= 0 || dot == name.length() - 1) return OTHER;
		return BY_EXTENSION.getOrDefault(name.substring(dot + 1).toLowerCase(Locale.ROOT), OTHER);
	}
}
