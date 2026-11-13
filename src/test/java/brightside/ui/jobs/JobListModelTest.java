package brightside.ui.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import brightside.JobList;
import convex.core.data.ACell;
import convex.core.data.Blob;
import convex.core.data.Index;
import convex.core.data.Maps;

/**
 * The Jobs list materialises only what is on screen: a list of thousands of
 * jobs, laid out and painted in a short viewport, projects a screenful of
 * rows from the lattice and no more. Headless: nothing is put on a desktop.
 */
class JobListModelTest {

	private static final int JOBS = 5000;

	@Test
	void aLongListProjectsOnlyTheRowsOnScreen() throws Exception {
		Index<Blob, ACell> index = Index.none();
		long start = 1_790_000_000_000L;
		for (int i = 0; i < JOBS; i++) {
			Blob id = JobList.idAt(start + i * 1000L);
			index = index.assoc(id, Maps.of("status", "COMPLETE", "created", start + i * 1000L, "op", "v/ops/test/echo"));
		}
		JobList.View view = JobList.view(index, "did:key:owner", JobList.Filter.ALL, start + JOBS * 1000L, 0);
		assertEquals(JOBS, view.size());

		JobListModel model = new JobListModel();
		JobRenderer renderer = new JobRenderer();
		SwingUtilities.invokeAndWait(() -> {
			JList<JobList.Job> list = new JList<>(model);
			JobsScreen.configure(list, renderer);
			model.setView(view);
			JScrollPane scroll = new JScrollPane(list);
			int rowHeight = list.getFixedCellHeight();
			scroll.setSize(600, rowHeight * 8);
			scroll.doLayout();
			scroll.getViewport().doLayout();
			BufferedImage image = new BufferedImage(600, rowHeight * 8, BufferedImage.TYPE_INT_ARGB);
			scroll.paint(image.getGraphics());
			assertTrue(list.getPreferredSize().height >= rowHeight * JOBS, "the list is as tall as every job");
		});
		assertTrue(model.projected() > 0, "the rows on screen were projected");
		assertTrue(model.projected() <= 12, "and only those: " + model.projected() + " of " + JOBS);
	}
}
