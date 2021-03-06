package fi.csc.microarray.databeans.features;

import fi.csc.microarray.databeans.DataBean;
import fi.csc.microarray.exception.MicroarrayException;

public class NonexistingFeature extends BasicFeature{

	public NonexistingFeature(DataBean bean, FeatureProvider factory) {
		super(bean, factory);
	}

	public Iterable<Float> asFloats() throws MicroarrayException {
		return null;
	}

	public Iterable<String> asStrings() throws MicroarrayException {
		return null;
	}

	public Table asTable() throws MicroarrayException {
		return null;
	}
}
