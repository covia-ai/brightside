package covia.brightside;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.lang.RT;
import convex.core.util.JSON;

/**
 * The last conversation, persisted per user so it reopens on restart. Stores
 * the displayed transcript and the agent session id (to continue the same
 * conversation with the assistant, preserving its memory of it).
 *
 * <p>One file per user at {@code <home>/conversations/<slug>.json}. This mirrors
 * the agent's own session purely for display and continuity; it is not the
 * source of truth for the model's context (the venue's session store is).
 */
public final class ConversationStore {

	private static final Logger log = LoggerFactory.getLogger(ConversationStore.class);

	/** A displayed turn. {@code role} is {@code "user"} or {@code "assistant"}. */
	public record Msg(String role, String text) {
	}

	private final Path file;
	private String sessionId;
	private final List<Msg> messages = new ArrayList<>();

	private ConversationStore(Path file) {
		this.file = file;
	}

	/** Loads the stored conversation for {@code slug}, or an empty one. */
	public static ConversationStore load(Path home, String slug) {
		Path file = home.resolve("conversations").resolve(slug + ".json");
		ConversationStore store = new ConversationStore(file);
		if (!Files.exists(file)) return store;
		try {
			AMap<AString, ACell> map = RT.ensureMap(JSON.parseJSON5(Files.readString(file)));
			if (map == null) return store;
			AString sid = RT.ensureString(map.get(Strings.create("sessionId")));
			store.sessionId = (sid != null) ? sid.toString() : null;
			AVector<ACell> msgs = RT.ensureVector(map.get(Strings.create("messages")));
			if (msgs != null) {
				for (long i = 0; i < msgs.count(); i++) {
					ACell m = msgs.get(i);
					AString role = RT.ensureString(RT.getIn(m, "role"));
					AString text = RT.ensureString(RT.getIn(m, "text"));
					if (role != null && text != null) store.messages.add(new Msg(role.toString(), text.toString()));
				}
			}
		} catch (Exception e) {
			log.warn("Could not read conversation {}: {}", file, e.toString());
		}
		return store;
	}

	public String sessionId() {
		return sessionId;
	}

	public void setSessionId(String sessionId) {
		this.sessionId = sessionId;
	}

	public List<Msg> messages() {
		return List.copyOf(messages);
	}

	public boolean isEmpty() {
		return messages.isEmpty();
	}

	/** Appends a turn, updates the session id and persists (one write). */
	public void record(String role, String text, String sessionId) {
		messages.add(new Msg(role, text));
		this.sessionId = sessionId;
		save();
	}

	/** Clears the conversation (a fresh chat) and persists. */
	public void clear() {
		messages.clear();
		sessionId = null;
		save();
	}

	private void save() {
		try {
			Files.createDirectories(file.getParent());
			AVector<ACell> msgs = Vectors.empty();
			for (Msg m : messages) {
				msgs = msgs.conj(Maps.of("role", m.role(), "text", m.text()));
			}
			AMap<AString, ACell> map = Maps.of("sessionId", sessionId, "messages", msgs);
			Files.writeString(file, JSON.printPretty(map).toString() + "\n");
		} catch (IOException e) {
			log.warn("Could not save conversation {}: {}", file, e.toString());
		}
	}
}
