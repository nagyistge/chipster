package fi.csc.microarray.client.screen;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import fi.csc.microarray.client.ClientApplication;
import fi.csc.microarray.client.Session;
import fi.csc.microarray.constants.VisualConstants;
import fi.csc.microarray.exception.MicroarrayException;

public class ShowSourceScreen extends ScreenBase implements ClientApplication.SourceCodeListener {

	private JFrame frame = null;
	private JTextArea sourceText = new JTextArea();
	private ClientApplication application = Session.getSession().getApplication();
	private JScrollPane scrollPane;
	
	public JFrame getFrame() {
		// fetch source
		String[] opID = new String[1];
		opID[0] = (String)childScreenParameter;
		childScreenParameter = null;
		
		// TODO this is weird and should be fixed
		if (opID[0] == null) {
			if (frame != null) {
				return frame;
			}
		}
		
			
		// a new frame is created to get it on top of everything
		// there should be a better way for this
		if (frame != null) {
			frame.dispose();
		}
		
		frame = new JFrame();
		frame.setContentPane(getContentPane());
		frame.setTitle("Source Code");
		// fetch the source
		if (opID[0] != null) {
			try {
				application.fetchSourceFor(opID, this);
			} catch (MicroarrayException e) {
				application.reportException(e);
			}
		}
		
		return frame;
	}

	private JPanel getContentPane() {
		JPanel contentPane = new JPanel();
		contentPane.setLayout(new BorderLayout());
		sourceText.setText("Please wait while loading source code...");
		sourceText.setEditable(false);
		sourceText.setFont(VisualConstants.MONOSPACED_FONT);
		contentPane.setPreferredSize(new Dimension(800, 400));
		scrollPane = new JScrollPane(sourceText);
		contentPane.add(scrollPane, BorderLayout.CENTER);
		return contentPane;
	}

	public boolean hasFrame() {
		return frame != null;
	}

	public void updateSourceCodeAt(int index, String sourceCode) {
		sourceText.setText(sourceCode);
		sourceText.setCaretPosition(0); // reset scrollpane 
	}

}
