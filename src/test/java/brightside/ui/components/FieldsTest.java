package brightside.ui.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

import com.formdev.flatlaf.FlatClientProperties;

class FieldsTest {

	@Test
	void aFlagLastsUntilTheTextNextChanges() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			JTextField field = new JTextField("bad name");
			Fields.flag(field);
			assertEquals(FlatClientProperties.OUTLINE_ERROR, field.getClientProperty(FlatClientProperties.OUTLINE));
			field.setText("good-name");
			assertNull(field.getClientProperty(FlatClientProperties.OUTLINE));
			// The flag is gone with its listener: a later edit leaves a new one alone.
			Fields.flag(field);
			field.setText("again");
			assertNull(field.getClientProperty(FlatClientProperties.OUTLINE));
		});
	}

	@Test
	void aTextAreasScrollPaneCarriesTheOutline() throws Exception {
		SwingUtilities.invokeAndWait(() -> {
			JTextArea input = new JTextArea("{ not json");
			JScrollPane scroll = new JScrollPane(input);
			Fields.flag(scroll, input);
			assertEquals(FlatClientProperties.OUTLINE_ERROR, scroll.getClientProperty(FlatClientProperties.OUTLINE));
			input.append("}");
			assertNull(scroll.getClientProperty(FlatClientProperties.OUTLINE));
		});
	}
}
