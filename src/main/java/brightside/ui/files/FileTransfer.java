package brightside.ui.files;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.swing.JComponent;
import javax.swing.TransferHandler;

import brightside.files.DirectoryCache.Location;
import brightside.files.FileChanges;
import brightside.files.FileChanges.Item;

/**
 * Dragging files about the browser, and dropping files from this computer
 * into it. Within one place a drag moves, or copies with Ctrl held; between
 * places it copies, as between drives. Files dropped in from the computer are
 * copied in and the originals stay. Where a drop lands is the owner's to say:
 * a folder row or a folder in the sidebar takes it in, anywhere else in the
 * open folder takes it there. Nothing is dragged out of the app — a drag out
 * can't ask the system to copy rather than move — and nothing goes through
 * the clipboard.
 */
@SuppressWarnings("serial")
final class FileTransfer extends TransferHandler {

	/** The items a drag carries, from the browser's list to wherever it is dropped in the app. */
	private record Carried(List<Item> items) implements Transferable {
		@Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] {ITEMS}; }
		@Override public boolean isDataFlavorSupported(DataFlavor flavor) { return ITEMS.equals(flavor); }
		@Override public Object getTransferData(DataFlavor flavor) { return this; }
	}

	/** Known only inside this process: never offered to another app. */
	private static final DataFlavor ITEMS = local();
	/** The drag under way, readable while it hovers — some platforms keep a drag's data from targets until the drop. */
	private static Carried dragging;
	private final Supplier<List<Item>> selection;
	private final Function<TransferSupport, Location> target;
	private final FileActions actions;

	/**
	 * @param selection what a drag from here carries; empty for nothing
	 * @param target    the folder a drop at the support's location goes into; null where nothing may land
	 */
	FileTransfer(Supplier<List<Item>> selection, Function<TransferSupport, Location> target, FileActions actions) {
		this.selection = selection;
		this.target = target;
		this.actions = actions;
	}

	@Override public int getSourceActions(JComponent c) {
		return COPY_OR_MOVE;
	}

	@Override protected Transferable createTransferable(JComponent c) {
		List<Item> items = selection.get();
		dragging = items.isEmpty() ? null : new Carried(items);
		return dragging;
	}

	@Override protected void exportDone(JComponent source, Transferable data, int action) {
		// The drop's own handler did the move; there is nothing left here to remove.
		dragging = null;
	}

	@Override public void exportToClipboard(JComponent c, Clipboard clipboard, int action) {
	}

	@Override public boolean canImport(TransferSupport support) {
		if (!support.isDrop()) return false;
		Location into = target.apply(support);
		if (into == null || into.root().readOnly()) return false;
		if (support.isDataFlavorSupported(ITEMS)) {
			if (dragging == null) return false;
			boolean copy = copies(dragging.items(), into, support);
			for (Item item : dragging.items()) {
				if (copy ? !FileChanges.canCopy(item, into) : !FileChanges.canMove(item, into)) return false;
			}
			return act(support, copy ? COPY : MOVE);
		}
		return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && act(support, COPY);
	}

	@Override public boolean importData(TransferSupport support) {
		if (!canImport(support)) return false;
		Location into = target.apply(support);
		if (support.isDataFlavorSupported(ITEMS)) {
			List<Item> items = dragging.items();
			if (support.getDropAction() == MOVE) actions.move(items, into);
			else actions.copy(items, into);
			return true;
		}
		try {
			List<?> files = (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
			actions.bringIn(files.stream().filter(File.class::isInstance).map(f -> ((File) f).toPath()).toList(), into);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/** Whether a drop copies: always between places, and within one when the person asks with Ctrl. */
	private static boolean copies(List<Item> items, Location into, TransferSupport support) {
		return !items.get(0).location().root().equals(into.root()) || support.getUserDropAction() == COPY;
	}

	private static DataFlavor local() {
		try {
			return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + Carried.class.getName(),
				"Brightside files", Carried.class.getClassLoader());
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The drop does {@code action}, if the drag allows it. */
	private static boolean act(TransferSupport support, int action) {
		if ((support.getSourceDropActions() & action) == 0) return false;
		support.setDropAction(action);
		return true;
	}
}
