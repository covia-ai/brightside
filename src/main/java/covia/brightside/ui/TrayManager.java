package covia.brightside.ui;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.SwingUtilities;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import covia.brightside.BrightSide;

/**
 * BrightSide's system-tray presence: the icon the window minimises to, with a
 * Show / Open venue / Exit menu. Clicking the icon brings the window back.
 *
 * <p>Best-effort, as in Covia's own venue tray: on a headless JVM, an
 * unsupported desktop, or under {@code BRIGHTSIDE_NO_TRAY=1}, {@link #install}
 * returns null and the window simply behaves as a normal window (minimise
 * minimises, close exits). A tray failure must never take the app down.
 */
public final class TrayManager {

	private static final Logger log = LoggerFactory.getLogger(TrayManager.class);

	/** Windows caps tray tooltips at 127 chars. */
	private static final int TOOLTIP_MAX = 127;

	private final TrayIcon icon;
	private boolean hiddenNoticeShown;

	private TrayManager(TrayIcon icon) {
		this.icon = icon;
	}

	/** Adds the tray icon. Runs on the event thread. Returns null when there is no usable tray. */
	public static TrayManager install(BrightSide app) {
		if ("1".equals(System.getenv("BRIGHTSIDE_NO_TRAY"))) return null;
		try {
			if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
				log.info("System tray not available — running as a plain window");
				return null;
			}
			PopupMenu menu = new PopupMenu();
			MenuItem show = new MenuItem("Show " + BrightSide.APP_NAME);
			show.addActionListener(e -> app.showWindow());
			menu.add(show);
			MenuItem open = new MenuItem("Open dashboard in browser");
			open.addActionListener(e -> app.openDashboard());
			menu.add(open);
			menu.addSeparator();
			MenuItem exit = new MenuItem("Exit");
			exit.addActionListener(e -> app.exit());
			menu.add(exit);

			SystemTray tray = SystemTray.getSystemTray();
			Dimension size = tray.getTrayIconSize();
			int px = (size != null && size.width > 0) ? size.width : 16;
			TrayIcon icon = new TrayIcon(Icons.icon(px), BrightSide.APP_NAME, menu);
			// The tray ACTION (double-click on Windows, Enter when focused) and a
			// plain left click both mean "get me back to the window".
			icon.addActionListener(e -> app.showWindow());
			icon.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent e) {
					if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) app.showWindow();
				}
			});
			tray.add(icon);
			log.info("Tray icon installed");
			return new TrayManager(icon);
		} catch (Throwable t) {
			// AWT can fail in exotic ways (missing native libs, odd DEs) — never fatal.
			log.warn("System tray unavailable: {}", t.toString());
			return null;
		}
	}

	/** Hover text, clamped to the platform limit. */
	public void setTooltip(String text) {
		icon.setToolTip(text.length() <= TOOLTIP_MAX ? text : text.substring(0, TOOLTIP_MAX - 1) + "…");
	}

	/** Called when the window is hidden to the tray; shows a one-time hint. */
	public void notifyHidden() {
		if (hiddenNoticeShown) return;
		hiddenNoticeShown = true;
		try {
			icon.displayMessage(BrightSide.APP_NAME,
				"Still running in the tray. Click the icon to reopen; use Exit to stop the venue.",
				TrayIcon.MessageType.INFO);
		} catch (Exception e) {
			// balloons are decoration
		}
	}

	public void remove() {
		try {
			SystemTray.getSystemTray().remove(icon);
		} catch (Throwable t) {
			// already gone, or the tray went away
		}
	}
}
