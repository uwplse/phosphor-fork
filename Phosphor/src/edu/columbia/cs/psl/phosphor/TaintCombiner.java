package edu.columbia.cs.psl.phosphor;

import edu.columbia.cs.psl.phosphor.runtime.Taint;

public interface TaintCombiner {
	Taint combineTags(Taint o1, Taint o2);
	void combineTagsInPlace(Object o, Taint t);
}
