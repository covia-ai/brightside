package brightside.ui.files;

import java.nio.file.Path;
import java.util.Map;

/**
 * Where files can live, as the person manages them from the Files screen:
 * folders on this computer, saved for the next start, and personal drives,
 * made at once. Every method but {@link #folderNameProblem} may touch disk or
 * the venue, so runs off the event thread.
 */
public interface Places {

	/** The folders saved for the next start, by name: true where read-only. */
	Map<String, Boolean> savedFolders() throws Exception;

	void addFolder(String name, Path folder, boolean readOnly) throws Exception;

	void setReadOnly(String name, boolean readOnly) throws Exception;

	/** Takes the folder away from Brightside; its files stay on this computer. */
	void removeFolder(String name) throws Exception;

	void createDrive(String name) throws Exception;

	/** Why {@code name} can't name a folder, or null when it can. */
	String folderNameProblem(String name);

	/** Why {@code name} can't name a drive, or null when it can. */
	String driveNameProblem(String name);

	/** A name for {@code folder}, from its own. */
	String suggestFolderName(Path folder);
}
