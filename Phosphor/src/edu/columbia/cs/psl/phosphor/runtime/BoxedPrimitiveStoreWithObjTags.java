package edu.columbia.cs.psl.phosphor.runtime;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import edu.columbia.cs.psl.phosphor.struct.TaintedBooleanWithObjTag;
import edu.columbia.cs.psl.phosphor.struct.TaintedByteWithObjTag;
import edu.columbia.cs.psl.phosphor.struct.TaintedCharWithObjTag;
import edu.columbia.cs.psl.phosphor.struct.TaintedShortWithObjTag;

public class BoxedPrimitiveStoreWithObjTags {
	private static class IdentityWrapper {
		private final int hash;
		private final Object referent;
		public IdentityWrapper(Object referent) {
			this.referent = referent;
			this.hash = System.identityHashCode(referent);
		}
		
		@Override
		public int hashCode() {
			return hash;
		}
		
		@Override
		public boolean equals(Object obj) {
			if(obj == null) {
				return false;
			}
			if(obj.getClass() != this.getClass()) {
				return false;
			}
			Object o2 = ((IdentityWrapper)obj).referent;
			return referent == o2;
		}
	}
	
	public static Map<IdentityWrapper, Object> tags = Collections.synchronizedMap(new WeakHashMap<IdentityWrapper, Object>());

	public static TaintedBooleanWithObjTag booleanValue(Boolean z) {
		TaintedBooleanWithObjTag ret = new TaintedBooleanWithObjTag();
		ret.val = z.booleanValue();
		IdentityWrapper w = new IdentityWrapper(z);
		if (tags.containsKey(w)) {
			ret.taint = tags.get(w);
		}
		return ret;
	}

	public static TaintedByteWithObjTag byteValue(Byte z) {
		TaintedByteWithObjTag ret = new TaintedByteWithObjTag();
		ret.val = z.byteValue();
		IdentityWrapper w = new IdentityWrapper(z);
		if (tags.containsKey(w))
			ret.taint = tags.get(w);
		return ret;
	}

	public static TaintedShortWithObjTag shortValue(Short z) {
		TaintedShortWithObjTag ret = new TaintedShortWithObjTag();
		ret.val = z.shortValue();
		IdentityWrapper w = new IdentityWrapper(z);
		if (tags.containsKey(w))
			ret.taint = tags.get(w);
		return ret;
	}

	public static TaintedCharWithObjTag charValue(Character z) {
		TaintedCharWithObjTag ret = new TaintedCharWithObjTag();
		ret.val = z.charValue();
		IdentityWrapper w = new IdentityWrapper(z);
		if (tags.containsKey(w))
			ret.taint = tags.get(w);
		return ret;
	}

	public static Boolean valueOf(Object tag, boolean z) {
		if (tag != null) {
			Boolean r = new Boolean(z);
			tags.put(new IdentityWrapper(r), tag);
			return r;
		}
		return Boolean.valueOf(z);
	}

	public static Byte valueOf(Object tag, byte z) {
		if (tag != null) {
			Byte r = new Byte(z);
			tags.put(new IdentityWrapper(r), tag);
			return r;
		}
		return Byte.valueOf(z);
	}

	public static Character valueOf(Object tag, char z) {
		if (tag != null) {
			Character r = new Character(z);
			tags.put(new IdentityWrapper(r), tag);
			return r;
		}
		return Character.valueOf(z);
	}

	public static Short valueOf(Object tag, short z) {
		if (tag != null) {
			Short r = new Short(z);
			tags.put(new IdentityWrapper(r), tag);
			return r;
		}
		return Short.valueOf(z);
	}
	
	// these are for tainting in multi mode
	
	public static int putTaint(Object i, Object tag) {
		if(tag == null) {
			return 0;
		}
		tags.put(new IdentityWrapper(i), tag);
		return 1;
	}
	
	public static Object getTaint(Object i, int f) {
		if(f == 0) {
			return null;
		}
		return tags.get(new IdentityWrapper(i));
	}
}
