package fi.csc.microarray.client.visualisation.methods.gbrowser;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.net.URL;

import fi.csc.microarray.client.visualisation.methods.gbrowser.fileFormat.SAMFile;

/**
 * Handler for files accessing SAM files.
 * 
 * @author naktinis
 *
 */
public class SAMDataSource extends DataSource {
    
    SAMFile samFile = null;
    
    
    public SAMDataSource(URL url) throws FileNotFoundException {
        // TODO Support URLs
        super(url);
    }

    public SAMDataSource(File file) throws FileNotFoundException {
        super(file);
        samFile = new SAMFile(file);
    }
    
    public SAMDataSource(URL urlRoot, String path)
            throws FileNotFoundException, MalformedURLException {
        super(urlRoot, path);
    }

    public SAMDataSource(File fileRoot, String path)
            throws FileNotFoundException, MalformedURLException {
        this(new File(fileRoot, path));
    }
    
    public SAMFile getSAM() {
        return samFile;
    }
}
