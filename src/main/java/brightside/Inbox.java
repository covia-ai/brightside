package brightside;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.MapEntry;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.grid.Job;
import covia.grid.Venue;
import covia.grid.hitl.Hitl;

/**
 * The owner's human-in-the-loop inbox ({@code h/}): what agents are asking this
 * user to decide, read in-process from the lattice, and the response operation
 * that resolves a request under the user's own authority — never an agent's.
 */
public final class Inbox {

	private static final String OP_RESPOND = "v/ops/hitl/respond";
	private static final long TIMEOUT_SECONDS = 30;

	/** A capability offered for a choice: a path (bare = the owner's own namespace), an ability, expiry in unix seconds (null = venue default). */
	public record Grant(String with, String can, Long exp) {
	}

	public record Option(String id, String label, List<Grant> grants) {
	}

	/**
	 * One typed question: {@code text}, {@code approval}, {@code choice},
	 * {@code checkboxes} or {@code token}. Grants sit on approval asks and on
	 * options; {@code tokenCaps} is what a token ask wants signed.
	 */
	public record Ask(String id, String type, String prompt, boolean required, boolean allowComment,
			List<Option> options, List<Grant> grants, List<Grant> tokenCaps) {

		public Option option(String optionId) {
			for (Option o : options) if (o.id().equals(optionId)) return o;
			return null;
		}
	}

	/** How a resolved request went: outcome, answers rendered by ask id, comment, grants conferred. */
	public record Response(String outcome, Map<String, String> answers, String comment, List<Grant> grants) {
	}

	/**
	 * A request in an inbox. Times are epoch millis ({@code expires} 0 = none);
	 * {@code response} is null while open. {@code owner} is the DID whose inbox
	 * holds it — the owner's own, or the venue's when the app answers as the
	 * operator (Odin asks his owner, the venue) — and null when unknown.
	 */
	public record Request(String id, String from, String agent, String title, String description,
			List<Ask> asks, String status, long created, long expires, Response response, String owner) {

		public boolean open() {
			return Hitl.OPEN.toString().equals(status);
		}
	}

	/**
	 * The owner's answer: values by ask id (Boolean for approval, String for text
	 * and choice, List of option ids for checkboxes), per-ask comments, the
	 * offered grants explicitly echoed, and an overall comment.
	 */
	public record Answer(Map<String, Object> answers, Map<String, String> comments, List<Grant> echoes, String comment) {
	}

	private Inbox() {
	}

	/** Every request in the inbox value (id → record): open ones newest first, then the rest newest first. */
	public static List<Request> parse(ACell inbox) {
		return parse(inbox, null);
	}

	/** As {@link #parse(ACell)}, stamping each request with the DID whose inbox this is. */
	public static List<Request> parse(ACell inbox, String owner) {
		List<Request> all = new ArrayList<>();
		if (inbox instanceof AMap<?, ?> m) {
			for (long i = 0; i < m.count(); i++) {
				MapEntry<?, ?> e = m.entryAt(i);
				Request r = request(e.getKey().toString(), (ACell) e.getValue(), owner);
				if (r != null) all.add(r);
			}
		}
		return order(all);
	}

	/** Several inboxes as one list — the owner's and the venue's — in the same order as {@link #parse}. */
	@SafeVarargs
	public static List<Request> merge(List<Request>... inboxes) {
		List<Request> all = new ArrayList<>();
		for (List<Request> l : inboxes) all.addAll(l);
		return order(all);
	}

	private static List<Request> order(List<Request> requests) {
		List<Request> open = new ArrayList<>();
		List<Request> done = new ArrayList<>();
		for (Request r : requests) (r.open() ? open : done).add(r);
		Comparator<Request> newest = Comparator.comparingLong(Request::created).reversed();
		open.sort(newest);
		done.sort(newest);
		List<Request> all = new ArrayList<>(open);
		all.addAll(done);
		return List.copyOf(all);
	}

	/** How many requests are waiting for the owner. */
	public static int pending(List<Request> requests) {
		int n = 0;
		for (Request r : requests) if (r.open()) n++;
		return n;
	}

	/** Answers an open request as the inbox owner ({@code hitl:respond}) and waits for the venue to resolve it. */
	public static void answer(Venue client, String id, Answer answer) throws Exception {
		respond(client, answerInput(id, answer));
	}

	/** Rejects an open request; the reason travels to the requester as its job's failure. */
	public static void reject(Venue client, String id, String reason) throws Exception {
		respond(client, Hitl.reject(id, blankToNull(reason)));
	}

	/** The {@code hitl:respond} input for an answer — echoed grants are exactly what the owner ticked. */
	static AMap<AString, ACell> answerInput(String id, Answer answer) {
		Hitl.ResponseBuilder b = Hitl.answer(id);
		for (Map.Entry<String, Object> e : answer.answers().entrySet()) {
			Object v = e.getValue();
			if (v instanceof Boolean approved) {
				b.answer(e.getKey(), approved.booleanValue());
			} else if (v instanceof String s) {
				b.answer(e.getKey(), s);
			} else if (v instanceof List<?> ids) {
				b.select(e.getKey(), ids.stream().map(String::valueOf).toArray(String[]::new));
			} else {
				throw new IllegalArgumentException("Unsupported answer for '" + e.getKey() + "': " + v);
			}
		}
		for (Map.Entry<String, String> c : answer.comments().entrySet()) {
			if (blankToNull(c.getValue()) != null) b.commentOn(c.getKey(), c.getValue().trim());
		}
		for (Grant g : answer.echoes()) b.echo(g.with(), g.can());
		if (blankToNull(answer.comment()) != null) b.comment(answer.comment().trim());
		return b.build();
	}

	private static void respond(Venue client, AMap<AString, ACell> input) throws Exception {
		Job job = client.invoke(OP_RESPOND, input).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		job.future().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	// ------------------------------------------------------------------
	// Record → model
	// ------------------------------------------------------------------

	private static Request request(String key, ACell rec, String owner) {
		if (!(rec instanceof AMap)) return null;
		List<Ask> asks = asks(RT.getIn(rec, Hitl.ASKS));
		String id = str(RT.getIn(rec, Hitl.ID));
		String title = str(RT.getIn(rec, Hitl.TITLE));
		return new Request((id != null) ? id : key, str(RT.getIn(rec, Hitl.FROM)), str(RT.getIn(rec, Hitl.AGENT)),
			(title != null) ? title : "(untitled request)", str(RT.getIn(rec, Hitl.DESCRIPTION)), asks,
			str(RT.getIn(rec, Hitl.STATUS)), lng(RT.getIn(rec, Hitl.CREATED)), lng(RT.getIn(rec, Hitl.EXPIRES)),
			response(RT.getIn(rec, Hitl.RESPONSE), asks), owner);
	}

	private static List<Ask> asks(ACell cell) {
		List<Ask> out = new ArrayList<>();
		if (cell instanceof AVector<?> v) {
			for (long i = 0; i < v.count(); i++) {
				ACell a = (ACell) v.get(i);
				String id = str(RT.getIn(a, Hitl.ID));
				if (id == null) continue;
				String prompt = str(RT.getIn(a, Hitl.PROMPT));
				out.add(new Ask(id, str(RT.getIn(a, Hitl.TYPE)), (prompt != null) ? prompt : id,
					CVMBool.TRUE.equals(RT.getIn(a, Hitl.REQUIRED)), CVMBool.TRUE.equals(RT.getIn(a, Hitl.COMMENT)),
					options(RT.getIn(a, Hitl.OPTIONS)), grants(RT.getIn(a, Hitl.GRANTS)),
					grants(RT.getIn(a, Hitl.TOKEN_ASK, Hitl.CAPS))));
			}
		}
		return List.copyOf(out);
	}

	private static List<Option> options(ACell cell) {
		List<Option> out = new ArrayList<>();
		if (cell instanceof AVector<?> v) {
			for (long i = 0; i < v.count(); i++) {
				ACell o = (ACell) v.get(i);
				String id = str(RT.getIn(o, Hitl.ID));
				if (id == null) continue;
				String label = str(RT.getIn(o, Hitl.LABEL));
				out.add(new Option(id, (label != null) ? label : id, grants(RT.getIn(o, Hitl.GRANTS))));
			}
		}
		return List.copyOf(out);
	}

	private static List<Grant> grants(ACell cell) {
		List<Grant> out = new ArrayList<>();
		if (cell instanceof AVector<?> v) {
			for (long i = 0; i < v.count(); i++) {
				ACell g = (ACell) v.get(i);
				String with = str(RT.getIn(g, Hitl.WITH));
				String can = str(RT.getIn(g, Hitl.CAN));
				if (with == null || can == null) continue;
				ACell exp = RT.getIn(g, Hitl.EXP);
				out.add(new Grant(with, can, (exp instanceof CVMLong l) ? l.longValue() : null));
			}
		}
		return List.copyOf(out);
	}

	private static Response response(ACell resp, List<Ask> asks) {
		if (!(resp instanceof AMap)) return null;
		Map<String, String> answers = new LinkedHashMap<>();
		for (Ask ask : asks) {
			ACell a = RT.getIn(resp, Hitl.ANSWERS, ask.id());
			if (a != null) answers.put(ask.id(), renderAnswer(ask, a));
		}
		return new Response(str(RT.getIn(resp, Hitl.OUTCOME)), answers, str(RT.getIn(resp, Hitl.COMMENT)),
			grants(RT.getIn(resp, Hitl.GRANTS)));
	}

	/** An answer as the owner would read it: Approved/Declined, option labels, or the text. */
	static String renderAnswer(Ask ask, ACell answer) {
		if (answer instanceof CVMBool b) return b.booleanValue() ? "Approved" : "Declined";
		if (answer instanceof AString s) {
			Option o = ask.option(s.toString());
			return (o != null) ? o.label() : s.toString();
		}
		if (answer instanceof AVector<?> v) {
			List<String> labels = new ArrayList<>();
			for (long i = 0; i < v.count(); i++) {
				String id = String.valueOf(v.get(i));
				Option o = ask.option(id);
				labels.add((o != null) ? o.label() : id);
			}
			return String.join(", ", labels);
		}
		return JSON.toStringPretty(answer);
	}

	private static String str(ACell cell) {
		AString s = RT.ensureString(cell);
		return (s != null) ? s.toString() : null;
	}

	private static long lng(ACell cell) {
		return (cell instanceof CVMLong l) ? l.longValue() : 0;
	}

	private static String blankToNull(String s) {
		return (s == null || s.isBlank()) ? null : s;
	}
}
