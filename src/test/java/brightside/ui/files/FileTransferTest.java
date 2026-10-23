package brightside.ui.files;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import javax.swing.TransferHandler;

import org.junit.jupiter.api.Test;

class FileTransferTest {

	/** Its in-process flavour is built when the class loads: a failure there would take the Files screen with it. */
	@Test void loadsAndOffersMoveOrCopy() {
		FileTransfer transfer = new FileTransfer(List::of, support -> null, null);
		assertEquals(TransferHandler.COPY_OR_MOVE, transfer.getSourceActions(null));
	}
}
