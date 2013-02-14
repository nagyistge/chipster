package fi.csc.microarray.client.visualisation.methods.gbrowser.track;

import java.awt.Color;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import fi.csc.microarray.client.visualisation.methods.gbrowser.dataSource.DataSource;
import fi.csc.microarray.client.visualisation.methods.gbrowser.drawable.Drawable;
import fi.csc.microarray.client.visualisation.methods.gbrowser.drawable.TextDrawable;
import fi.csc.microarray.client.visualisation.methods.gbrowser.fileFormat.ColumnType;
import fi.csc.microarray.client.visualisation.methods.gbrowser.gui.GBrowserView;
import fi.csc.microarray.client.visualisation.methods.gbrowser.message.AreaResult;

/**
 * Track for placing title texts on top of other tracks.
 *
 */
public class TitleTrack extends Track {

	private Color color;
	private String title;

	public TitleTrack(GBrowserView view, String title, Color color) {
		super(view, null);
		this.color = color;
		this.title = title;
		layoutHeight = 10;
	}

	@Override
	public Collection<Drawable> getDrawables() {
		Collection<Drawable> drawables = getEmptyDrawCollection();
		drawables.add(new TextDrawable(5, 10, title, color));
		return drawables;
	}

	public void processAreaResult(AreaResult areaResult) {
		// ignore
	}

	@Override
	public int getHeight() {
		return 10;
	}

    @Override
    public Map<DataSource, Set<ColumnType>> requestedData() {
        return null;
    }

	@Override
	public String getName() {
		return "title";
	}
}