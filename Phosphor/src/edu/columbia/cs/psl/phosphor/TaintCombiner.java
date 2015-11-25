package edu.columbia.cs.psl.phosphor;

import edu.columbia.cs.psl.phosphor.runtime.Taint;
import edu.columbia.cs.psl.phosphor.struct.ControlTaintTagStack;

public interface TaintCombiner {
	public Taint combineTags(Taint o1, Taint o2);
	public void combineTagsInPlace(Object o, Taint t);
	public void combineTagsOnObject(Object o, ControlTaintTagStack tags);
	public Taint combineTags(Taint t1, ControlTaintTagStack tags);
}
