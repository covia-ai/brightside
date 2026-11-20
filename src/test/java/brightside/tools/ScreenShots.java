package brightside.tools;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import brightside.BrightSide;
import brightside.Inbox;
import brightside.ui.MainWindow;
import brightside.ui.onboarding.OnboardingWizard;

/**
 * The screenshot harness: boots Tau in a scratch data directory with the
 * offline echo model, walks the screens at each window size, and paints the
 * window into PNGs for side-by-side review between branches. A developer tool,
 * not a test: it puts a window on the desktop while it runs. Never points at
 * {@code ~/.brightside} and never uses a real key.
 *
 * <pre>
 * java -cp target/test-classes:target/classes:$(cat target/cp.txt) \
 *     brightside.tools.ScreenShots &lt;outDir&gt; &lt;dark|light&gt; [nameFilter]
 * </pre>
 *
 * Screens are reached through the window's own navigation methods
 * ({@link Screens}), so the harness follows the shell as it changes.
 */
public final class ScreenShots {

	/** Window sizes from the state matrix: narrow, today's default, the new default, wide. */
	public static final List<Dimension> SIZES = List.of(
		new Dimension(800, 600), new Dimension(1040, 760), new Dimension(1200, 800), new Dimension(1440, 900));

	/** Only screens whose name contains this are written (third argument); empty for all. */
	static String filter = "";

	private ScreenShots() {
	}

	public static void main(String[] args) throws Exception {
		Path out = Path.of(args.length > 0 ? args[0] : "target/shots");
		String mode = args.length > 1 ? args[1] : "dark";
		filter = args.length > 2 ? args[2] : "";
		Files.createDirectories(out);
		Path scratch = Files.createTempDirectory("tau-shots-");
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		Path config = scratch.resolve("config.json");
		Files.writeString(config, """
			{
				"theme": "%s",
				"venue": { "name": "Tau", "port": %d },
				"chat": { "llmOperation": "v/test/ops/llm" }
			}
			""".formatted(mode, port));
		System.out.println("Scratch data directory: " + scratch);

		BrightSide.main(new String[] { config.toString() });
		MainWindow window = waitFor(() -> frame(MainWindow.class), 30_000);
		Screens screens = new Screens(window);

		// Onboarding, every card that needs no venue.
		for (Dimension size : SIZES) {
			screens.resize(size);
			for (String step : screens.onboardingSteps()) {
				screens.onboardingStep(step);
				shoot(window, out, mode, "onboarding-" + step.toLowerCase(), size);
			}
		}

		// Finish onboarding as a new person called Sam with the echo model.
		char[] pass = "scratch-only-passphrase".toCharArray();
		String seed = "11".repeat(32);
		OnboardingWizard.Setup setup = new OnboardingWizard.Setup(pass, seed, "Sam", null, null, null);
		BrightSide app = screens.app();
		SwingUtilities.invokeAndWait(() -> app.onOnboardingComplete(setup));
		waitFor(() -> screens.chatReady() ? Boolean.TRUE : null, 120_000);
		Thread.sleep(1500);

		for (Dimension size : SIZES) {
			screens.resize(size);
			for (Map.Entry<String, Runnable> e : screens.loggedIn().entrySet()) {
				e.getValue().run();
				shoot(window, out, mode, e.getKey(), size);
			}
		}
		// A conversation with the echo model, then Home again with it in view.
		screens.sendMessage("Hello, what can you do?");
		Thread.sleep(4000);
		for (Dimension size : SIZES) {
			screens.resize(size);
			screens.home();
			shoot(window, out, mode, "home-chat", size);
			screens.sessions();
			shoot(window, out, mode, "sessions-chat", size);
		}
		// The Inbox with seeded requests: one, twelve and over a hundred waiting.
		for (int n : new int[] { 1, 12, 120 }) {
			screens.seedInbox(n);
			for (Dimension size : SIZES) {
				screens.resize(size);
				screens.inbox();
				shoot(window, out, mode, "inbox-" + n, size);
				screens.home();
				shoot(window, out, mode, "home-inbox-" + n, size);
			}
		}
		System.out.println("Screenshots written to " + out.toAbsolutePath());
		System.exit(0);
	}

	/** Paints the window's root pane, as laid out at {@code size}, into a PNG. */
	static void shoot(JFrame window, Path out, String mode, String name, Dimension size) throws Exception {
		if (!name.contains(filter)) return;
		Thread.sleep(400);
		AtomicReference<BufferedImage> img = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			JComponent root = window.getRootPane();
			root.validate();
			BufferedImage b = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = b.createGraphics();
			root.printAll(g);
			g.dispose();
			img.set(b);
		});
		Path file = out.resolve(mode).resolve(name + "-" + size.width + "x" + size.height + ".png");
		Files.createDirectories(file.getParent());
		ImageIO.write(img.get(), "png", file.toFile());
		System.out.println("  " + file);
	}

	@SuppressWarnings("unchecked")
	static <T extends Frame> T frame(Class<T> type) {
		for (Frame f : Frame.getFrames()) if (type.isInstance(f) && f.isShowing()) return (T) f;
		return null;
	}

	interface Probe<T> {
		T get() throws Exception;
	}

	static <T> T waitFor(Probe<T> probe, long millis) throws Exception {
		long end = System.currentTimeMillis() + millis;
		while (System.currentTimeMillis() < end) {
			T t = probe.get();
			if (t != null) return t;
			Thread.sleep(200);
		}
		throw new IllegalStateException("Timed out waiting");
	}

	/**
	 * How the harness reaches each screen: through the window's own methods,
	 * by reflection, so production code carries no test hooks.
	 */
	static final class Screens {
		final MainWindow window;

		Screens(MainWindow window) {
			this.window = window;
		}

		BrightSide app() throws Exception {
			return (BrightSide) field(window, "app");
		}

		void resize(Dimension size) throws Exception {
			SwingUtilities.invokeAndWait(() -> {
				window.setExtendedState(Frame.NORMAL);
				window.setSize(size);
				window.validate();
			});
			Thread.sleep(300);
		}

		List<String> onboardingSteps() throws Exception {
			List<String> out = new ArrayList<>();
			Class<?> step = Class.forName("brightside.ui.onboarding.OnboardingWizard$Step");
			for (Object s : step.getEnumConstants()) out.add(((Enum<?>) s).name());
			return out;
		}

		void onboardingStep(String name) throws Exception {
			Object wizard = field(window, "onboarding");
			Class<?> stepType = Class.forName("brightside.ui.onboarding.OnboardingWizard$Step");
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object step = Enum.valueOf((Class) stepType, name);
			Method show = wizard.getClass().getDeclaredMethod("show", stepType);
			show.setAccessible(true);
			onEdt(() -> show.invoke(wizard, step));
		}

		boolean chatReady() throws Exception {
			return "chat".equals(field(window, "currentCard")) && field(window, "venue") != null;
		}

		/** Every screen and Settings page reachable after login, by name, in order. */
		Map<String, Runnable> loggedIn() throws Exception {
			Map<String, Runnable> out = new java.util.LinkedHashMap<>();
			Class<?> tabType = Class.forName("brightside.ui.NavBar$Tab");
			for (Object t : tabType.getEnumConstants()) {
				String n = ((Enum<?>) t).name();
				if (n.equals("SETTINGS")) continue; // each page below
				out.put(n.toLowerCase(), () -> quietly(() -> { tab(n); return null; }));
			}
			Class<?> pageType = Class.forName("brightside.ui.settings.SettingsScreen$Tab");
			for (Object p : pageType.getEnumConstants()) {
				String n = ((Enum<?>) p).name();
				out.put("settings-" + n.toLowerCase(), () -> quietly(() -> { settings(n); return null; }));
			}
			return out;
		}

		void home() throws Exception {
			tab("HOME");
		}

		void sessions() throws Exception {
			tab("SESSIONS");
		}

		void inbox() throws Exception {
			tab("INBOX");
		}

		void tab(String name) throws Exception {
			Class<?> tabType = Class.forName("brightside.ui.NavBar$Tab");
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object tab = Enum.valueOf((Class) tabType, name);
			Method m = MainWindow.class.getDeclaredMethod("selectTab", tabType);
			m.setAccessible(true);
			onEdt(() -> m.invoke(window, tab));
		}

		void settings(String name) throws Exception {
			Class<?> pageType = Class.forName("brightside.ui.settings.SettingsScreen$Tab");
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object page = Enum.valueOf((Class) pageType, name);
			Method m = MainWindow.class.getDeclaredMethod("showSettings", pageType);
			m.setAccessible(true);
			onEdt(() -> m.invoke(window, page));
		}

		void sendMessage(String text) throws Exception {
			home();
			Object chat = field(window, "chatPanel");
			JTextComponent input = (JTextComponent) field(chat, "input");
			AbstractButton send = (AbstractButton) field(chat, "send");
			onEdt(() -> {
				input.setText(text);
				send.doClick();
				return null;
			});
		}

		/** Sample requests only: they never reach the venue. */
		void seedInbox(int n) throws Exception {
			List<Inbox.Request> requests = new ArrayList<>();
			long now = System.currentTimeMillis();
			for (int i = 0; i < n; i++) {
				Inbox.Ask ask = new Inbox.Ask("a" + i, "approval", "Send the weekly summary to two recipients?", true,
					true, List.of(), List.of(), List.of());
				requests.add(new Inbox.Request("r" + i, "did:key:z6MkSample" + i, "Release notes",
					"Send email to 2 recipients", "The weekly summary is ready to send.", List.of(ask), covia.grid.hitl.Hitl.OPEN.toString(),
					now - i * 60_000L, now + 28 * 60_000L, null, null));
			}
			onEdt(() -> {
				window.setInbox(requests);
				return null;
			});
		}

		interface Call {
			Object call() throws Exception;
		}

		static void onEdt(Call c) throws Exception {
			AtomicReference<Exception> err = new AtomicReference<>();
			SwingUtilities.invokeAndWait(() -> {
				try {
					c.call();
				} catch (Exception e) {
					err.set(e);
				}
			});
			if (err.get() != null) throw err.get();
		}

		static void quietly(Call c) {
			try {
				c.call();
			} catch (Exception e) {
				System.err.println("  could not reach screen: " + e);
			}
		}

		static Object field(Object o, String name) throws Exception {
			Class<?> c = o.getClass();
			while (c != null) {
				try {
					Field f = c.getDeclaredField(name);
					f.setAccessible(true);
					return f.get(o);
				} catch (NoSuchFieldException e) {
					c = c.getSuperclass();
				}
			}
			throw new NoSuchFieldException(name);
		}
	}
}
