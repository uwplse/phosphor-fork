package edu.columbia.cs.psl.phosphor.instrumenter;

import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import edu.columbia.cs.psl.phosphor.Configuration;
import edu.columbia.cs.psl.phosphor.Instrumenter;
import edu.columbia.cs.psl.phosphor.TaintUtils;
import edu.columbia.cs.psl.phosphor.instrumenter.analyzer.NeverNullArgAnalyzerAdapter;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.AnnotationVisitor;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.ClassVisitor;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.FieldVisitor;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.Label;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.MethodVisitor;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.Opcodes;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.Type;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.commons.AnalyzerAdapter;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.commons.GeneratorAdapter;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.AnnotationNode;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.FieldNode;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.FrameNode;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.LabelNode;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.LocalVariableNode;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.MethodNode;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.ParameterNode;
import edu.columbia.cs.psl.phosphor.runtime.BoxedPrimitiveStoreWithObjTags;
import edu.columbia.cs.psl.phosphor.runtime.NativeHelper;
import edu.columbia.cs.psl.phosphor.runtime.TaintChecker;
import edu.columbia.cs.psl.phosphor.runtime.TaintInstrumented;
import edu.columbia.cs.psl.phosphor.runtime.TaintSentinel;
import edu.columbia.cs.psl.phosphor.struct.ControlTaintTagStack;
import edu.columbia.cs.psl.phosphor.struct.TaintedWithIntTag;
import edu.columbia.cs.psl.phosphor.struct.TaintedWithObjTag;
import edu.columbia.cs.psl.phosphor.struct.multid.MultiDTaintedArray;
import edu.columbia.cs.psl.phosphor.struct.multid.MultiDTaintedArrayWithIntTag;
import edu.columbia.cs.psl.phosphor.struct.multid.MultiDTaintedArrayWithObjTag;

/**
 * CV responsibilities: Add a field to classes to track each instance's taint
 * Add a method for each primitive returning method to return the taint of that
 * return Add a field to hold temporarily the return taint of each primitive
 * 
 * @author jon
 * 
 */
public class TaintTrackingClassVisitor extends ClassVisitor {
	public static boolean IS_RUNTIME_INST = true;
	public static boolean FIELDS_ONLY = false;
	public static boolean GEN_HAS_TAINTS_METHOD = false;
	public static final boolean NATIVE_BOX_UNBOX = true;
	
	static boolean DO_OPT = false;
	static {
		if (!DO_OPT && !IS_RUNTIME_INST)
			System.err.println("WARN: OPT DISABLED");
		
	}
	List<FieldNode> fields;
	private boolean ignoreFrames;
	public TaintTrackingClassVisitor(ClassVisitor cv, boolean skipFrames, List<FieldNode> fields) {
		super(Opcodes.ASM5, cv);
		DO_OPT = DO_OPT && !IS_RUNTIME_INST;
		this.ignoreFrames = skipFrames;
		this.fields = fields;
	}
	
	private LinkedList<MethodNode> methodsToAddWrappersFor = new LinkedList<MethodNode>();
	private String className;
	private boolean isNormalClass;
	private boolean isInterface;
	private boolean addTaintMethod;
	private boolean isAnnotation;

	private boolean isAbstractClass;

	private boolean implementsComparable;

	private boolean implementsSerializable;

	private boolean fixLdcClass;
	private boolean actuallyAddField;
	private boolean addTaintCarry = false;
	private boolean isUnwrapClass;
	
	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		addTaintField = true;
		addTaintMethod = true;
		isUnwrapClass = name.startsWith("sun/security/pkcs11") || name.equals("freemarker/core/FMParserTokenManager") || name.startsWith("java/lang/ThreadLocal");
		this.fixLdcClass = (version & 0xFFFF) < Opcodes.V1_5;
		if(Instrumenter.IS_KAFFE_INST && name.endsWith("java/lang/VMSystem"))
			access = access | Opcodes.ACC_PUBLIC;
		else if(Instrumenter.IS_HARMONY_INST && name.endsWith("java/lang/VMMemoryManager"))
		{
			access = access & ~Opcodes.ACC_PRIVATE;
			access = access | Opcodes.ACC_PUBLIC;
		}
		if ((access & Opcodes.ACC_ABSTRACT) != 0) {
			isAbstractClass = true;
		}
		if ((access & Opcodes.ACC_INTERFACE) != 0) {
			addTaintField = false;
			isInterface = true;
		}
		if ((access & Opcodes.ACC_ENUM) != 0)
			addTaintField = false;

		if ((access & Opcodes.ACC_ANNOTATION) != 0)
			isAnnotation = true;

		if (!superName.equals("java/lang/Object") && !Instrumenter.isIgnoredClass(superName)) {
			addTaintField = false;
			addTaintMethod = true;
		}
		if (name.equals("java/awt/image/BufferedImage") || name.equals("java/awt/image/Image"))
			addTaintField = false;
		if (addTaintField)
			addTaintMethod = true;
		if ((superName.equals("java/lang/Object") || Instrumenter.isIgnoredClass(superName)) && !isInterface && !isAnnotation) {
			generateEquals = true;
			generateHashCode = true;
		}
		isNormalClass = (access & Opcodes.ACC_ENUM) == 0 && (access & Opcodes.ACC_INTERFACE) == 0;
		
		this.actuallyAddField = name.equals("java/lang/Integer") || name.equals("java/lang/Long") || name.equals("java/lang/Double")
			|| name.equals("java/lang/String") || name.equals("java/lang/Float") || Configuration.AUTO_TAINT;

		if (isNormalClass && !Instrumenter.isIgnoredClass(name) && !FIELDS_ONLY && actuallyAddField) {
			this.addTaintCarry = Configuration.AUTO_TAINT && !Arrays.asList(interfaces).contains("edu/washington/cse/instrumentation/runtime/TaintCarry");
			String[] newIntfcs = new String[interfaces.length + (addTaintCarry ? 2 : 1)];
			System.arraycopy(interfaces, 0, newIntfcs, 0, interfaces.length);
			newIntfcs[interfaces.length] = Type.getInternalName((Configuration.MULTI_TAINTING ? TaintedWithObjTag.class : TaintedWithIntTag.class));
			if(addTaintCarry) {
				newIntfcs[interfaces.length + 1] = "edu/washington/cse/instrumentation/runtime/TaintCarry";
			}
			interfaces = newIntfcs;
			if (signature != null)
				signature = signature + Type.getDescriptor((Configuration.MULTI_TAINTING ? TaintedWithObjTag.class : TaintedWithIntTag.class));
		}
		this.visitAnnotation(Type.getDescriptor(TaintInstrumented.class), true);

		//		System.out.println("V " + version);
		for (String s : interfaces) {
			if (s.equals(Type.getInternalName(Comparable.class)))
				implementsComparable = true;
			else if (s.equals(Type.getInternalName(Serializable.class)))
				implementsSerializable = true;
		}
		super.visit(version, access, name, signature, superName, interfaces);
		
		if(Instrumenter.isIgnoredClass(superName) && !isInterface)
		{
			//Might need to override stuff.
			Class c;
			try {
				c = Class.forName(superName.replace("/", "."));
				for(Method m : c.getMethods())
				{
					superMethodsToOverride.put(m.getName()+Type.getMethodDescriptor(m), m);
				}
			} catch (ClassNotFoundException e) {
				e.printStackTrace();
			}
		
		}
		this.className = name;
		
	}

	boolean generateHashCode = false;
	boolean generateEquals = false;
	boolean isProxyClass = false;

	private HashMap<String, Method> superMethodsToOverride = new HashMap<String, Method>();
	HashMap<MethodNode, MethodNode> forMore = new HashMap<MethodNode, MethodNode>();
	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		if (TaintUtils.DEBUG_CALLS || TaintUtils.DEBUG_FIELDS || TaintUtils.DEBUG_FRAMES || TaintUtils.DEBUG_LOCAL)
			System.out.println("Instrumenting " + name + "\n\n\n\n\n\n");
		
		if(name.endsWith(TaintUtils.METHOD_SUFFIX)) {
			return super.visitMethod(access, name, desc, signature, exceptions);
		}
		if(Configuration.taintTagFactory.isIgnoredMethod(className, name, desc)) {
			return super.visitMethod(access, name, desc, signature, exceptions); 
		}
		
		superMethodsToOverride.remove(name + desc);

		if(Instrumenter.IS_KAFFE_INST && className.equals("java/lang/VMSystem"))
			access = access | Opcodes.ACC_PUBLIC;
		else if(Instrumenter.IS_HARMONY_INST && className.endsWith("java/lang/VMMemoryManager"))
		{
			access = access & ~Opcodes.ACC_PRIVATE;
			access = access | Opcodes.ACC_PUBLIC;
		}
		if (name.equals("equals") && desc.equals("(Ljava/lang/Object;)Z"))
			generateEquals = false;
		if (name.equals("hashCode") && desc.equals("()I"))
			generateHashCode = false;

		String originalName = name;
		if (FIELDS_ONLY) { // || isAnnotation
			return super.visitMethod(access, name, desc, signature, exceptions);
		}

		if (originalName.contains("$$INVIVO")) {
			name = name + "_orig";
		}
		if (name.equals("compareTo"))
			implementsComparable = false;

		if (name.equals("hasAnyTaints"))
			isProxyClass = true;

		//We will need to add shadow args for each parameter that is a primitive. Because that worked so well last time.
		Type[] argTypes = Type.getArgumentTypes(desc);
		LinkedList<Type> newArgTypes = new LinkedList<Type>();
		boolean isRewrittenDesc = false;
		for (Type t : argTypes) {
			if (t.getSort() == Type.ARRAY) {
				if (t.getElementType().getSort() != Type.OBJECT) {
					if (t.getDimensions() > 1) {
						newArgTypes.add(MultiDTaintedArray.getTypeForType(t));
						isRewrittenDesc = true;
						continue;
					} else {
						newArgTypes.add(Type.getType(Configuration.TAINT_TAG_ARRAYDESC));
						isRewrittenDesc = true;
					}
				}
			} else if (t.getSort() != Type.OBJECT) {
				isRewrittenDesc = true;
				newArgTypes.add(Type.getType(Configuration.TAINT_TAG_DESC));
			}
			newArgTypes.add(t);
		}
		if(Configuration.IMPLICIT_TRACKING && !name.equals("<clinit>"))
		{
			isRewrittenDesc = true;
			newArgTypes.add(Type.getType(ControlTaintTagStack.class));
		}
		if (isRewrittenDesc && name.equals("<init>"))
			newArgTypes.add(Type.getType(TaintSentinel.class));
		//If we are rewriting the return type, also add a param to pass for pre-alloc
		Type oldReturnType = Type.getReturnType(desc);
		Type newReturnType = TaintUtils.getContainerReturnType(Type.getReturnType(desc));
		if((oldReturnType.getSort() != Type.VOID && oldReturnType.getSort() != Type.OBJECT && oldReturnType.getSort() != Type.ARRAY) || (oldReturnType.getSort() == Type.ARRAY 
				&& oldReturnType.getElementType().getSort() != Type.OBJECT && oldReturnType.getDimensions() == 1))
		{
			newArgTypes.add(newReturnType);
		}
		Type[] newArgs = new Type[newArgTypes.size()];
		newArgs = newArgTypes.toArray(newArgs);

		boolean requiresNoChange = !isRewrittenDesc && newReturnType.equals(Type.getReturnType(desc));
		MethodNode wrapper = new MethodNode(access, name, desc, signature, exceptions);
		if (!requiresNoChange && !name.equals("<clinit>") && !(name.equals("<init>") && !isRewrittenDesc))
			methodsToAddWrappersFor.add(wrapper);

		String newDesc = Type.getMethodDescriptor(newReturnType, newArgs);
		//		System.out.println("olddesc " + desc + " newdesc " + newDesc);
		if ((access & Opcodes.ACC_NATIVE) == 0 && !methodIsTooBigAlready(name, desc) && (!isUnwrapClass || (access & Opcodes.ACC_ABSTRACT) != 0)) {
			//not a native method
			if (!name.contains("<") && !requiresNoChange)
				name = name + TaintUtils.METHOD_SUFFIX;
//			if(className.equals("sun/misc/URLClassPath$JarLoader"))
//				System.out.println("\t\t:"+name+newDesc);
			MethodVisitor mv = super.visitMethod(access, name, newDesc, signature, exceptions);
			
			if(
				(!className.equals("sun/misc/VM") || !name.startsWith("isBooted")) &&
				!className.startsWith(Type.getInternalName(ThreadLocal.class)) &&
				!className.startsWith(Type.getInternalName(WeakReference.class)) && 
				!className.startsWith("java/util/concurrent/locks") &&
				!className.startsWith("edu/columbia/cs/psl/phosphor/") &&
				Configuration.AUTO_TAINT
			) {
				
//				System.out.println(className + "." + name + ":" + newDesc);
				ReturnPropagateMV rmv = new ReturnPropagateMV(mv, ignoreFrames, access, className, newDesc); 
//				mv = new ReturnPropagateMV(mv, ignoreFrames, access, className, newDesc);
				mv = rmv.aa = new AnalyzerAdapter(className, access, name, newDesc, rmv);
			}
			
			mv = new TaintTagFieldCastMV(mv);

			MethodVisitor rootmV = mv;
			mv = new SourceSinkTaintingMV(mv, access, className, name, newDesc, desc);
			//			mv = new CheckMethodAdapter(mv);
//			mv = new SpecialOpcodeRemovingMV(mv,ignoreFrames, className);

//			mv = reflectionMasker;
			//			PropertyDebug debug = new PropertyDebug(Opcodes.ASM4, mv, access, name, newDesc,className);
			MethodVisitor optimizer;
			optimizer = mv;

//			if (DO_OPT)
//				optimizer = new PopOptimizingMV(mv, access, className, name, newDesc, signature, exceptions);
			mv = new SpecialOpcodeRemovingMV(optimizer,ignoreFrames, className, fixLdcClass);
//			optimizer = new PopOptimizingMV(mv, access,className, name, newDesc, signature, exceptions);

			NeverNullArgAnalyzerAdapter analyzer = new NeverNullArgAnalyzerAdapter(className, access, name, newDesc, mv);
			mv = new StringTaintVerifyingMV(analyzer,(implementsSerializable || className.startsWith("java/nio/") || className.startsWith("java/io/BufferedInputStream") || className.startsWith("sun/nio")),analyzer); //TODO - how do we handle directbytebuffers?

			ReflectionHidingMV reflectionMasker = new ReflectionHidingMV(mv, className,analyzer);

			PrimitiveBoxingFixer boxFixer = new PrimitiveBoxingFixer(access, className, name, desc, signature, exceptions, reflectionMasker, analyzer);
			LocalVariableManager lvs;
			TaintPassingMV tmv;
			MethodVisitor nextMV;
			{
//				ImplicitTaintRemoverMV implicitCleanup = new ImplicitTaintRemoverMV(access, className, name, desc, signature, exceptions, boxFixer, analyzer);
				tmv = new TaintPassingMV(boxFixer, access, className, name, newDesc, signature, exceptions, desc, analyzer,rootmV);
				tmv.setFields(fields);
				TaintAdapter custom = null;
				lvs = new LocalVariableManager(access, newDesc, tmv, analyzer,mv);

				nextMV = lvs;
				if(Configuration.extensionMethodVisitor != null)
				{
					try {
						custom = Configuration.extensionMethodVisitor.getConstructor(Integer.TYPE,String.class, String.class, String.class, String.class, String[].class, MethodVisitor.class,
								NeverNullArgAnalyzerAdapter.class).newInstance(Opcodes.ASM5, className, name, desc, signature, exceptions, nextMV, analyzer);
						nextMV = custom;
					} catch (InstantiationException e) {
						e.printStackTrace();
					} catch (IllegalAccessException e) {
						e.printStackTrace();
					} catch (IllegalArgumentException e) {
						e.printStackTrace();
					} catch (InvocationTargetException e) {
						e.printStackTrace();
					} catch (NoSuchMethodException e) {
						e.printStackTrace();
					} catch (SecurityException e) {
						e.printStackTrace();
					}
				}
				if(custom != null)
					custom.setLocalVariableSorter(lvs);

				nextMV = new ConstantValueNullTaintGenerator(className, access, name, newDesc, signature, exceptions, nextMV);
			}

			MethodArgReindexer mar = new MethodArgReindexer(nextMV, access, name, newDesc, desc, wrapper);
			CallRewritingMethodVisitor crmv = new CallRewritingMethodVisitor(className, access, name, desc, signature, exceptions, mar);
			PrimitiveArrayAnalyzer primitiveArrayFixer = new PrimitiveArrayAnalyzer(className, access, name, desc, signature, exceptions, crmv);
			NeverNullArgAnalyzerAdapter preAnalyzer = new NeverNullArgAnalyzerAdapter(className, access, name, desc, primitiveArrayFixer);

			MethodVisitor mvNext = preAnalyzer;
			if (!IS_RUNTIME_INST && TaintUtils.OPT_IGNORE_EXTRA_TAINTS)
				if (Configuration.IMPLICIT_TRACKING)
					mvNext = new ImplicitUnnecessaryTaintLoadRemover(className, access, name, desc, signature, exceptions, preAnalyzer);
				else
					mvNext = new UnnecessaryTaintLoadRemover(className, access, name, desc, signature, exceptions, preAnalyzer);
			else
				mvNext = preAnalyzer;
			primitiveArrayFixer.setAnalyzer(preAnalyzer);
			boxFixer.setLocalVariableSorter(lvs);
			tmv.setArrayAnalyzer(primitiveArrayFixer);
			tmv.setLVOffset(mar.getNewArgOffset());
			tmv.setLocalVariableSorter(lvs);
			lvs.setPrimitiveArrayAnalyzer(primitiveArrayFixer); // i'm lazy. this guy will tell the LVS what return types to prealloc
			reflectionMasker.setLvs(lvs);
			
			//			if(IS_RUNTIME_INST)
			//			{
			//				return mvNext;
			//			}
			final MethodVisitor prev = mvNext;
			MethodNode rawMethod = new MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions) {
				@Override
				public void visitEnd() {
					super.visitEnd();
					this.accept(prev);
				}
			};
			if (!isInterface && !originalName.contains("$$INVIVO"))
				this.myMethods.add(rawMethod);
			forMore.put(wrapper,rawMethod);
			return rawMethod;
		} else {
			//this is a native method. we want here to make a $taint method that will call the original one.
			final MethodVisitor prev = super.visitMethod(access, name, desc, signature, exceptions);
			MethodNode rawMethod = new MethodNode(Opcodes.ASM5, access, name, desc, signature, exceptions) {
				@Override
				public void visitEnd() {
					super.visitEnd();
					this.accept(prev);
				}
			};
			forMore.put(wrapper, rawMethod);
			return rawMethod;
		}
		
	}

	private boolean methodIsTooBigAlready(String name, String desc) {
		// TODO we need to implement something to detect massive constant array loads and optimize it. for now... just this :-/
		return false;
	}

	private LinkedList<FieldNode> extraFieldsToVisit = new LinkedList<FieldNode>();
	private LinkedList<FieldNode> myFields = new LinkedList<FieldNode>();
	private LinkedList<MethodNode> myMethods = new LinkedList<MethodNode>();
	boolean hasSerialUID = false;

	@Override
	public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
		Type fieldType = Type.getType(desc);
		if (TaintUtils.getShadowTaintType(desc) != null) {
			if(TaintAdapter.canRawTaintAccess(className) || (access & Opcodes.ACC_STATIC) != 0) {
				extraFieldsToVisit.add(new FieldNode(access, name + TaintUtils.TAINT_FIELD, TaintUtils.getShadowTaintType(desc), null, null));
			} else {
				extraFieldsToVisit.add(new FieldNode(access,  name+TaintUtils.TAINT_FIELD, (fieldType.getSort() == Type.ARRAY ? "[":"")+TaintAdapter.getTagType(className).getDescriptor(), null, null));
			}
		} else if (!FIELDS_ONLY && fieldType.getSort() == Type.ARRAY && fieldType.getElementType().getSort() != Type.OBJECT && fieldType.getDimensions() > 1) {
			desc = MultiDTaintedArray.getTypeForType(fieldType).getDescriptor();
		}
		if (!hasSerialUID && name.equals("serialVersionUID"))
			hasSerialUID = true;
		if((access & Opcodes.ACC_STATIC) == 0)
			myFields.add(new FieldNode(access, name, desc, signature, value));
		return super.visitField(access, name, desc, signature, value);
	}

	boolean addTaintField = false;

	@Override
	public void visitEnd() {

		boolean goLightOnGeneratedStuff = !Instrumenter.IS_ANDROID_INST && className.equals("java/lang/Byte");
//		if (isAnnotation) {
//			super.visitEnd();
//			return;
//		}
		if (!hasSerialUID && !isInterface && !goLightOnGeneratedStuff) {
			if(!Configuration.MULTI_TAINTING)
				super.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "serialVersionUIDPHOSPHOR_TAG", Configuration.TAINT_TAG_DESC, null, 0);
			else
				super.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "serialVersionUIDPHOSPHOR_TAG", Configuration.TAINT_TAG_DESC, null, null);
		}
		//Add a field to track the instance's taint
		if (addTaintField && !goLightOnGeneratedStuff && this.actuallyAddField) {
			
			if(!Configuration.MULTI_TAINTING) {
				super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.TAINT_FIELD, "I", null, 0);
			} else {
				super.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_VOLATILE, TaintUtils.TAINT_FIELD, TaintAdapter.getTagType(className).getDescriptor(), null, null);
			}
//			if(GEN_HAS_TAINTS_METHOD){
//			super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.HAS_TAINT_FIELD, "Z", null, 0);
//			super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.IS_TAINT_SEATCHING_FIELD, "Z", null, 0);
//			}
		}
		if(this.className.equals("java/lang/reflect/Method"))
			super.visitField(Opcodes.ACC_PUBLIC, TaintUtils.TAINT_FIELD+"marked", "Z", null, false);
		for (FieldNode fn : extraFieldsToVisit) {
			if (className.equals("java/lang/Byte") && !fn.name.startsWith("value"))
				continue;
			if (isNormalClass) {
				fn.access = fn.access & ~Opcodes.ACC_FINAL;
				fn.access = fn.access & ~Opcodes.ACC_PRIVATE;
				fn.access = fn.access & ~Opcodes.ACC_PROTECTED;
				fn.access = fn.access | Opcodes.ACC_PUBLIC;
			}
			if ((fn.access & Opcodes.ACC_STATIC) != 0) {
				if (fn.desc.equals("I"))
					super.visitField(fn.access, fn.name, fn.desc, fn.signature, 0);
				else
					super.visitField(fn.access, fn.name, fn.desc, fn.signature, null);
			} else
				super.visitField(fn.access, fn.name, fn.desc, fn.signature, null);
		}
		if(FIELDS_ONLY)
			return;
		if ((isAbstractClass || isInterface) && implementsComparable && !goLightOnGeneratedStuff) {
			//Need to add this to interfaces so that we can call it on the interface
			if(Configuration.IMPLICIT_TRACKING)
				super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "compareTo$$PHOSPHORTAGGED", "(Ljava/lang/Object;"+Type.getDescriptor(ControlTaintTagStack.class)+Configuration.TAINTED_INT_DESC+")" + Configuration.TAINTED_INT_DESC, null, null);
			else
				super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "compareTo$$PHOSPHORTAGGED", "(Ljava/lang/Object;"+Configuration.TAINTED_INT_DESC+")" + Configuration.TAINTED_INT_DESC, null, null);
		}

		if (generateEquals && !goLightOnGeneratedStuff) {
			superMethodsToOverride.remove("equals(Ljava/lang/Object;)Z");
			methodsToAddWrappersFor.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, "equals", "(Ljava/lang/Object;)Z", null, null));
			MethodVisitor mv;
			mv = super.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
			mv.visitCode();
			Label start = new Label();
			Label end = new Label();
			mv.visitLabel(start);
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "equals", "(Ljava/lang/Object;)Z",false);
			mv.visitLabel(end);
			mv.visitInsn(Opcodes.IRETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
			mv.visitLocalVariable("this", "L"+className+";", null, start, end, 0);
			mv.visitLocalVariable("other", "Ljava/lang/Object;", null, start, end, 1);
		}
		if (generateHashCode && !goLightOnGeneratedStuff) {
			superMethodsToOverride.remove("hashCode()I");
			methodsToAddWrappersFor.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_NATIVE, "hashCode", "()I", null, null));
			MethodVisitor mv;
			mv = super.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
			mv.visitCode();
			Label start = new Label();
			Label end = new Label();
			mv.visitLabel(start);
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "hashCode", "()I",false);
			mv.visitLabel(end);
			mv.visitInsn(Opcodes.IRETURN);
			mv.visitMaxs(0, 0);
			mv.visitEnd();
			mv.visitLocalVariable("this", "L"+className+";", null, start, end, 0);

		}
		if (addTaintMethod) {
			if (isInterface) {
//				super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "get" + TaintUtils.TAINT_FIELD, "()"+(Configuration.MULTI_TAINTING ? "Ljava/lang/Object;" : "I"), null, null);
//				if(GEN_HAS_TAINTS_METHOD)
//					super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "hasAnyTaints", "()Z", null, null);
//				super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "set" + TaintUtils.TAINT_FIELD, "("+(Configuration.MULTI_TAINTING ? "Ljava/lang/Object;" : "I")+")V", null, null);
			} else {
				MethodVisitor mv;
				if(!actuallyAddField) {
					/*mv = super.visitMethod(Opcodes.ACC_PUBLIC, "get" + TaintUtils.TAINT_FIELD, "()"+(Configuration.MULTI_TAINTING ? "Ljava/lang/Object;" : "I"), null, null);
					mv.visitCode();
					mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(UnsupportedOperationException.class));
					mv.visitInsn(Opcodes.DUP);
					mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(UnsupportedOperationException.class), "<init>", "()V", false);
					mv.visitInsn(Opcodes.ATHROW);
					mv.visitMaxs(0, 0);
					mv.visitEnd();

					mv = super.visitMethod(Opcodes.ACC_PUBLIC, "set" + TaintUtils.TAINT_FIELD, "("+(Configuration.MULTI_TAINTING ? "Ljava/lang/Object;" : "I")+")V", null, null);
					mv.visitCode();
					mv.visitTypeInsn(Opcodes.NEW, Type.getInternalName(UnsupportedOperationException.class));
					mv.visitInsn(Opcodes.DUP);
					mv.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(UnsupportedOperationException.class), "<init>", "()V", false);
					mv.visitInsn(Opcodes.ATHROW);
					mv.visitMaxs(0, 0);
					mv.visitEnd();*/
				} else if (!Configuration.MULTI_TAINTING) {
					mv = super.visitMethod(Opcodes.ACC_PUBLIC, "get" + TaintUtils.TAINT_FIELD, "()" + (Configuration.MULTI_TAINTING ? "Ljava/lang/Object;" : "I"), null, null);
					mv.visitCode();
					mv.visitVarInsn(Opcodes.ALOAD, 0);
					mv.visitFieldInsn(Opcodes.GETFIELD, className, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);
					mv.visitInsn(Opcodes.IRETURN);
					mv.visitMaxs(0, 0);
					mv.visitEnd();

					mv = super.visitMethod(Opcodes.ACC_PUBLIC, "set" + TaintUtils.TAINT_FIELD, "(" + (Configuration.MULTI_TAINTING ? "Ljava/lang/Object;" : "I") + ")V", null, null);
					mv.visitCode();
					mv.visitVarInsn(Opcodes.ALOAD, 0);
					mv.visitVarInsn(Opcodes.ILOAD, 1);
					mv.visitFieldInsn(Opcodes.PUTFIELD, className, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);
					if (className.equals("java/lang/String")) {
						//Also overwrite the taint tag of all of the chars behind this string
						mv.visitVarInsn(Opcodes.ALOAD, 0);
						mv.visitFieldInsn(Opcodes.GETFIELD, className, "value" + TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_ARRAYDESC);
						mv.visitVarInsn(Opcodes.ILOAD, 1);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(TaintChecker.class), "setTaints", "([II)V", false);
					}
					mv.visitInsn(Opcodes.RETURN);
					mv.visitMaxs(0, 0);
					mv.visitEnd();
				} else if(Configuration.MULTI_TAINTING && !TaintAdapter.canRawTaintAccess(className) && !className.equals("java/lang/StackTraceElement")) {
					assert className.equals("java/lang/Float") ||	className.equals("java/lang/Double") ||className.equals("java/lang/Integer") ||	className.equals("java/lang/Long");
					{
						mv = super.visitMethod(Opcodes.ACC_PUBLIC, "get" + TaintUtils.TAINT_FIELD, "()" + "Ljava/lang/Object;", null, null);
						mv.visitCode();
						mv.visitVarInsn(Opcodes.ALOAD, 0);
						mv.visitFieldInsn(Opcodes.GETFIELD, className, "value" + TaintUtils.TAINT_FIELD, "I");
						Label l0 = new Label();
						mv.visitJumpInsn(Opcodes.IFNE, l0);
						mv.visitInsn(Opcodes.ACONST_NULL);
						mv.visitInsn(Opcodes.ARETURN);
						mv.visitLabel(l0);
						mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
						mv.visitVarInsn(Opcodes.ALOAD, 0);
						mv.visitInsn(Opcodes.ICONST_1);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(BoxedPrimitiveStoreWithObjTags.class), "getTaint", "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
						mv.visitInsn(Opcodes.ARETURN);
						mv.visitMaxs(1, 2);
						mv.visitEnd();
					}
					{
						mv = super.visitMethod(Opcodes.ACC_PUBLIC, "set" + TaintUtils.TAINT_FIELD, "(" + (Configuration.MULTI_TAINTING ? "Ljava/lang/Object;" : "I") + ")V", null, null);
						mv.visitCode();
						mv.visitVarInsn(Opcodes.ALOAD, 1);
						Label l0 = new Label();
						mv.visitJumpInsn(Opcodes.IFNONNULL, l0);
						mv.visitInsn(Opcodes.RETURN);
						mv.visitLabel(l0);
						mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
						mv.visitVarInsn(Opcodes.ALOAD, 0);
						mv.visitVarInsn(Opcodes.ALOAD, 1);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(BoxedPrimitiveStoreWithObjTags.class), "putTaint", "(Ljava/lang/Object;Ljava/lang/Object;)I", false);
						mv.visitInsn(Opcodes.POP);
						mv.visitVarInsn(Opcodes.ALOAD, 0);
						mv.visitInsn(Opcodes.ICONST_1);
						mv.visitFieldInsn(Opcodes.PUTFIELD, className, "value" + TaintUtils.TAINT_FIELD, "I");
						mv.visitInsn(Opcodes.RETURN);
						mv.visitMaxs(2, 2);
						mv.visitEnd();
					}
				} else {
					mv = super.visitMethod(Opcodes.ACC_PUBLIC, "get" + TaintUtils.TAINT_FIELD, "()" + (Configuration.MULTI_TAINTING ? "Ljava/lang/Object;" : "I"), null, null);
					mv = new TaintTagFieldCastMV(mv);
					mv.visitCode();
					mv.visitVarInsn(Opcodes.ALOAD, 0);
					mv.visitFieldInsn(Opcodes.GETFIELD, className, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);

					mv.visitInsn(Opcodes.ARETURN);
					mv.visitMaxs(1, 1);
					mv.visitEnd();

					mv = super.visitMethod(Opcodes.ACC_PUBLIC, "set" + TaintUtils.TAINT_FIELD, "(" + (Configuration.MULTI_TAINTING ? "Ljava/lang/Object;" : "I") + ")V", null, null);
					mv = new TaintTagFieldCastMV(mv);
					mv.visitCode();
					mv.visitVarInsn(Opcodes.ALOAD, 0);
					mv.visitVarInsn(Opcodes.ALOAD, 1);
					mv.visitTypeInsn(Opcodes.CHECKCAST, Configuration.TAINT_TAG_INTERNAL_NAME);
					mv.visitFieldInsn(Opcodes.PUTFIELD, className, TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_DESC);
					if (className.equals("java/lang/String")) {
						//Also overwrite the taint tag of all of the chars behind this string
						mv.visitVarInsn(Opcodes.ALOAD, 0);
//							mv.visitFieldInsn(Opcodes.GETFIELD, className, "value" + TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_ARRAYDESC);
						mv.visitVarInsn(Opcodes.ALOAD, 1);
						mv.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(TaintChecker.class), "setTaints", "(Ljava/lang/String;Ljava/lang/Object;)V", false);
					}
					mv.visitInsn(Opcodes.RETURN);
					mv.visitMaxs(2, 2);
					mv.visitEnd();
				}
			}
			
			if(Configuration.MULTI_TAINTING && Configuration.AUTO_TAINT && !isInterface && this.addTaintCarry) {
				MethodVisitor mv;
				{
					mv = super.visitMethod(Opcodes.ACC_PUBLIC, "_staccato_get_taint", "()Ljava/util/Map;", null, null);
					mv.visitCode();
					mv.visitVarInsn(Opcodes.ALOAD, 0);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "getPHOSPHOR_TAG", "()Ljava/lang/Object;", false);
					mv.visitTypeInsn(Opcodes.CHECKCAST, "edu/columbia/cs/psl/phosphor/runtime/Taint");
					mv.visitVarInsn(Opcodes.ASTORE, 1);
					mv.visitVarInsn(Opcodes.ALOAD, 1);
					mv.visitInsn(Opcodes.ACONST_NULL);
					Label l0 = new Label();
					mv.visitJumpInsn(Opcodes.IF_ACMPNE, l0);
					mv.visitInsn(Opcodes.ACONST_NULL);
					mv.visitInsn(Opcodes.ARETURN);
					mv.visitLabel(l0);
					if(!ignoreFrames) {
						mv.visitFrame(Opcodes.F_APPEND,1, new Object[] {"edu/columbia/cs/psl/phosphor/runtime/Taint"}, 0, null);
					}
					mv.visitVarInsn(Opcodes.ALOAD, 1);
					mv.visitFieldInsn(Opcodes.GETFIELD, "edu/columbia/cs/psl/phosphor/runtime/Taint", "lbl", "Ljava/lang/Object;");
					mv.visitTypeInsn(Opcodes.CHECKCAST, "java/util/Map");
					mv.visitInsn(Opcodes.ARETURN);
					mv.visitMaxs(2, 2);
					mv.visitEnd();
				}
				{
					mv = super.visitMethod(Opcodes.ACC_PUBLIC, "_staccato_set_taint", "(Ljava/util/Map;)V", null, null);
					mv.visitCode();
					mv.visitVarInsn(Opcodes.ALOAD, 1);
					mv.visitInsn(Opcodes.ACONST_NULL);
					Label l0 = new Label();
					mv.visitJumpInsn(Opcodes.IF_ACMPNE, l0);
					mv.visitVarInsn(Opcodes.ALOAD, 0);
					mv.visitInsn(Opcodes.ACONST_NULL);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "setPHOSPHOR_TAG", "(Ljava/lang/Object;)V", false);
					Label l1 = new Label();
					mv.visitJumpInsn(Opcodes.GOTO, l1);
					mv.visitLabel(l0);
					if(!ignoreFrames) {
						mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
					}
					mv.visitVarInsn(Opcodes.ALOAD, 0);
					mv.visitTypeInsn(Opcodes.NEW, "edu/columbia/cs/psl/phosphor/runtime/Taint");
					mv.visitInsn(Opcodes.DUP);
					mv.visitVarInsn(Opcodes.ALOAD, 1);
					mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "edu/columbia/cs/psl/phosphor/runtime/Taint", "<init>", "(Ljava/lang/Object;)V", false);
					mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, "setPHOSPHOR_TAG", "(Ljava/lang/Object;)V", false);
					mv.visitLabel(l1);
					if(!ignoreFrames) {
						mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
					}
					mv.visitInsn(Opcodes.RETURN);
					mv.visitMaxs(4, 2);
					mv.visitEnd();
				}
			}
		}
		

		if(Configuration.MULTI_TAINTING)
			generateStrLdcWrapper();
		if (!goLightOnGeneratedStuff)
			for (MethodNode m : methodsToAddWrappersFor) {
				if ((m.access & Opcodes.ACC_NATIVE) == 0 && (!isUnwrapClass || (m.access & Opcodes.ACC_ABSTRACT) != 0)) {
					if ((m.access & Opcodes.ACC_ABSTRACT) == 0) {
						//not native
						MethodNode fullMethod = forMore.get(m);

						Type origReturn = Type.getReturnType(m.desc);
						Type newReturn = TaintUtils.getContainerReturnType(origReturn);
						boolean needToPrealloc = TaintUtils.isPreAllocReturnType(m.desc);
						String[] exceptions = new String[m.exceptions.size()];
						exceptions = m.exceptions.toArray(exceptions);
						MethodVisitor mv = super.visitMethod(m.access, m.name, m.desc, m.signature, exceptions);
						mv = new TaintTagFieldCastMV(mv);

						//TODO maybe re-enable this
						if(fullMethod != null)
						{
							if(fullMethod.visibleAnnotations != null)
								for(AnnotationNode an : fullMethod.visibleAnnotations)
								{
									an.accept(mv.visitAnnotation(an.desc, true));
								}
							if(fullMethod.invisibleAnnotations != null)
								for(AnnotationNode an : fullMethod.invisibleAnnotations)
								{
									an.accept(mv.visitAnnotation(an.desc, false));
								}
////							if(fullMethod.visibleParameterAnnotations != null)
////								for(List<AnnotationNode> an : fullMethod.visibleParameterAnnotations)
////								{
////									an.accept(mv.visitParameterAnnotation(an., desc, visible));
////								}
							if (fullMethod.visibleLocalVariableAnnotations != null)
								for (AnnotationNode an : fullMethod.visibleLocalVariableAnnotations)
									an.accept(mv.visitAnnotation(an.desc, true));
							if (fullMethod.invisibleLocalVariableAnnotations != null)
								for (AnnotationNode an : fullMethod.invisibleLocalVariableAnnotations)
									an.accept(mv.visitAnnotation(an.desc, false));
							if (fullMethod.visibleTypeAnnotations != null)
								for (AnnotationNode an : fullMethod.visibleTypeAnnotations)
									an.accept(mv.visitAnnotation(an.desc, true));
							if (fullMethod.invisibleTypeAnnotations != null)
								for (AnnotationNode an : fullMethod.invisibleTypeAnnotations)
									an.accept(mv.visitAnnotation(an.desc, false));
							if (fullMethod.parameters != null)
								for (ParameterNode pn : fullMethod.parameters)
									pn.accept(mv);
							if (fullMethod.visibleParameterAnnotations != null)
								for (int i = 0; i < fullMethod.visibleParameterAnnotations.length; i++)
									if (fullMethod.visibleParameterAnnotations[i] != null)
										for (AnnotationNode an : fullMethod.visibleParameterAnnotations[i])
											an.accept(mv.visitParameterAnnotation(i, an.desc, true));
							if (fullMethod.invisibleParameterAnnotations != null)
								for (int i = 0; i < fullMethod.invisibleParameterAnnotations.length; i++)
									if (fullMethod.invisibleParameterAnnotations[i] != null)
										for (AnnotationNode an : fullMethod.invisibleParameterAnnotations[i])
											an.accept(mv.visitParameterAnnotation(i, an.desc, false));
						}
						NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, m.access, m.name, m.desc, mv);
						MethodVisitor soc = new SpecialOpcodeRemovingMV(an, false, className, false);
						LocalVariableManager lvs = new LocalVariableManager(m.access, m.desc, soc, an, mv);
						lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(newReturn));
						GeneratorAdapter ga = new GeneratorAdapter(lvs, m.access, m.name, m.desc);
						Label startLabel = new Label();
						ga.visitCode();
						ga.visitLabel(startLabel);
						ga.visitLineNumber(0, startLabel);
						
						Type[] argTypes = Type.getArgumentTypes(m.desc);
						int idx = 0;
						if ((m.access & Opcodes.ACC_STATIC) == 0) {
							ga.visitVarInsn(Opcodes.ALOAD, 0);
							idx++;
						}
						
						String newDesc = "(";
						for (Type t : argTypes) {
							boolean loaded = false;
							boolean needToBoxMultiD = false;
							if (t.getSort() == Type.ARRAY) {
								if (t.getElementType().getSort() != Type.OBJECT) {
									if (t.getDimensions() == 1) {
										newDesc += Configuration.TAINT_TAG_ARRAYDESC;
										ga.visitVarInsn(Opcodes.ALOAD, idx);
										TaintAdapter.createNewTaintArray(t.getDescriptor(), an, lvs, lvs);
										loaded = true;
									} else {
										newDesc += MultiDTaintedArray.getTypeForType(t).getDescriptor();
										needToBoxMultiD = true;
									}
								}
							} else if (t.getSort() != Type.OBJECT) {
								newDesc += Configuration.TAINT_TAG_DESC;
								Configuration.taintTagFactory.generateEmptyTaint(ga);
							}
							if (!loaded)
								ga.visitVarInsn(t.getOpcode(Opcodes.ILOAD), idx);
							if(NATIVE_BOX_UNBOX && t.getSort() == Type.OBJECT && Instrumenter.isCollection(t.getInternalName()))
							{
								////  public final static ensureIsBoxed(Ljava/util/Collection;)Ljava/util/Collection;
								ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(NativeHelper.class), "ensureIsBoxed", "(Ljava/util/Collection;)Ljava/util/Collection;",false);
								ga.visitTypeInsn(Opcodes.CHECKCAST, t.getInternalName());
							}
							if (!needToBoxMultiD)
								newDesc += t.getDescriptor();
							else {
//								Label isNull = new Label();
								Label isDone = new Label();
								ga.visitInsn(Opcodes.DUP);
								ga.visitJumpInsn(Opcodes.IFNULL, isDone);
								ga.visitIntInsn(Opcodes.BIPUSH, t.getElementType().getSort());
								ga.visitIntInsn(Opcodes.BIPUSH, t.getDimensions());
								ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName((Configuration.MULTI_TAINTING ? MultiDTaintedArrayWithObjTag.class : MultiDTaintedArrayWithIntTag.class)), "initWithEmptyTaints", "([Ljava/lang/Object;II)Ljava/lang/Object;",false);
								FrameNode fn = TaintAdapter.getCurrentFrameNode(an);
								fn.stack.set(fn.stack.size() -1,"java/lang/Object");
								ga.visitLabel(isDone);
								fn.accept(lvs);		
								ga.visitTypeInsn(Opcodes.CHECKCAST, MultiDTaintedArray.getTypeForType(t).getDescriptor());

							}
							idx += t.getSize();
						}
						if(Configuration.IMPLICIT_TRACKING)
						{
							newDesc += Type.getDescriptor(ControlTaintTagStack.class);
							ga.visitTypeInsn(Opcodes.NEW, Type.getInternalName(ControlTaintTagStack.class));
							ga.visitInsn(Opcodes.DUP);
							ga.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(ControlTaintTagStack.class), "<init>", "()V", false);
						}
						if (m.name.equals("<init>")) {
							newDesc += Type.getDescriptor(TaintSentinel.class);
							ga.visitInsn(Opcodes.ACONST_NULL);
						}
						if(needToPrealloc)
						{
							newDesc += newReturn.getDescriptor();
							an.visitVarInsn(Opcodes.ALOAD, lvs.getPreAllocedReturnTypeVar(newReturn));
						}
						newDesc += ")" + newReturn.getDescriptor();

						int opcode;
						if ((m.access & Opcodes.ACC_STATIC) == 0) {
							if ((m.access & Opcodes.ACC_PRIVATE) != 0 || m.name.equals("<init>"))
								opcode = Opcodes.INVOKESPECIAL;
							else
								opcode = Opcodes.INVOKEVIRTUAL;
						} else
							opcode = Opcodes.INVOKESTATIC;
						if (m.name.equals("<init>")) {
							ga.visitMethodInsn(Opcodes.INVOKESPECIAL, className, m.name, newDesc,false);
						} else
							ga.visitMethodInsn(opcode, className, m.name + TaintUtils.METHOD_SUFFIX, newDesc,false);

						//unbox collections
						idx =0;
						if ((m.access & Opcodes.ACC_STATIC) == 0) {
							idx++;
						}

						for (Type t : argTypes) {
							if(NATIVE_BOX_UNBOX && t.getSort() == Type.OBJECT && Instrumenter.isCollection(t.getInternalName()))
							{
								////  public final static ensureIsBoxed(Ljava/util/Collection;)Ljava/util/Collection;
								ga.visitVarInsn(t.getOpcode(Opcodes.ILOAD), idx);
								ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(NativeHelper.class), "ensureIsUnBoxed", "(Ljava/util/Collection;)Ljava/util/Collection;",false);
								ga.visitInsn(Opcodes.POP);
							}
							idx += t.getSize();
						}
						if (origReturn != newReturn) {
							String taintType = TaintUtils.getShadowTaintType(origReturn.getDescriptor());
							if (taintType != null) {
								//							ga.visitInsn(Opcodes.DUP);
								//							String taintTypeRaw = "I";
								//							if (origReturn.getSort() == Type.ARRAY)
								//								taintTypeRaw = "[I";
								//							ga.visitFieldInsn(Opcodes.GETFIELD, newReturn.getInternalName(), "taint", taintTypeRaw);
								//							ga.visitInsn(Opcodes.SWAP);
								ga.visitFieldInsn(Opcodes.GETFIELD, newReturn.getInternalName(), "val", origReturn.getDescriptor());
							} else {
								//Need to convert from [[WrapperForCArray to [[[C

								Label isDone = new Label();
								ga.visitInsn(Opcodes.DUP);
								ga.visitJumpInsn(Opcodes.IFNULL, isDone);
								ga.visitIntInsn(Opcodes.BIPUSH, origReturn.getElementType().getSort());
								ga.visitIntInsn(Opcodes.BIPUSH, origReturn.getDimensions()-1);
								ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName((Configuration.MULTI_TAINTING ? MultiDTaintedArrayWithObjTag.class : MultiDTaintedArrayWithIntTag.class)), "unboxVal", "(Ljava/lang/Object;II)Ljava/lang/Object;",false);
								FrameNode fn = TaintAdapter.getCurrentFrameNode(an);
								fn.stack.set(fn.stack.size() -1,"java/lang/Object");
								ga.visitLabel(isDone);
								fn.accept(lvs);
								ga.visitTypeInsn(Opcodes.CHECKCAST, origReturn.getInternalName());

							}
						}
						Label endLabel = new Label();
						ga.visitLabel(endLabel);
						ga.returnValue();
						ga.visitMaxs(0, 0);
//						int j = 0;
						for (LocalVariableNode n : m.localVariables) {
							ga.visitLocalVariable(n.name, n.desc, n.signature, startLabel, endLabel, n.index);
						}

						if (m.name.equals("<init>")) {

						}
						ga.visitEnd();
					} else {
						String[] exceptions = new String[m.exceptions.size()];
						exceptions = m.exceptions.toArray(exceptions);
						MethodNode fullMethod = forMore.get(m);

						MethodVisitor mv = super.visitMethod(m.access, m.name, m.desc, m.signature, exceptions);
						if(fullMethod.annotationDefault != null)
						{
							AnnotationVisitor av = mv.visitAnnotationDefault();
							AnnotationNode.accept(av, null, fullMethod.annotationDefault);
							av.visitEnd();
						}
						m.accept(mv);
						mv.visitEnd();
					}
				} else {

					//generate wrapper for native method - a native wrapper
					generateNativeWrapper(m);
					
				}
			}
		superMethodsToOverride.remove("wait(JI)V");
		superMethodsToOverride.remove("wait(J)V");
		superMethodsToOverride.remove("wait()V");
		superMethodsToOverride.remove("notify()V");
		superMethodsToOverride.remove("notifyAll()V");
		for (Method m : superMethodsToOverride.values()) {
			int acc = Opcodes.ACC_PUBLIC;
			if (Modifier.isProtected(m.getModifiers()) && isInterface)
				continue;
			else if (Modifier.isPrivate(m.getModifiers()))
				continue;
			if (Modifier.isStatic(m.getModifiers()))
				acc = acc | Opcodes.ACC_STATIC;
			if(isInterface)
				acc = acc | Opcodes.ACC_ABSTRACT;
			else
				acc = acc &~Opcodes.ACC_ABSTRACT;
//						System.out.println(m.getName() + " " + Type.getMethodDescriptor(m));
			MethodNode mn = new MethodNode(Opcodes.ASM5, acc, m.getName(), Type.getMethodDescriptor(m), null, null);

			generateNativeWrapper(mn);
		}
//		if (!goLightOnGeneratedStuff && TaintUtils.GENERATE_FASTPATH_VERSIONS)
//			for (final MethodNode m : myMethods) {
//				final String oldDesc = m.desc;
//				if (m.name.equals("<init>")) {
//					m.desc = m.desc.substring(0, m.desc.indexOf(")")) + Type.getDescriptor(UninstrumentedTaintSentinel.class) + ")" + Type.getReturnType(m.desc).getDescriptor();
//				} else if (m.name.equals("<clinit>")) {
//					continue;
//				} else {
//					m.name = m.name.replace(TaintUtils.METHOD_SUFFIX, "") + "$$INVIVO_UNINST";
//				}
//				if ((m.access & Opcodes.ACC_ABSTRACT) != 0 && !isInterface) {
//					//Let's see what happens if we make these non-abstract, with no body, to try to fix
//					//problems with jasper usage.
//					m.access = m.access & ~Opcodes.ACC_ABSTRACT;
//					m.instructions = new InsnList();
//					Type ret = Type.getReturnType(m.desc);
//					switch (ret.getSort()) {
//					case Type.BOOLEAN:
//					case Type.BYTE:
//					case Type.CHAR:
//					case Type.SHORT:
//					case Type.INT:
//						m.instructions.add(new InsnNode(Opcodes.ICONST_0));
//						m.instructions.add(new InsnNode(Opcodes.IRETURN));
//						break;
//					case Type.DOUBLE:
//						m.instructions.add(new InsnNode(Opcodes.DCONST_0));
//						m.instructions.add(new InsnNode(Opcodes.DRETURN));
//						break;
//					case Type.FLOAT:
//						m.instructions.add(new InsnNode(Opcodes.FCONST_0));
//						m.instructions.add(new InsnNode(Opcodes.FRETURN));
//						break;
//					case Type.LONG:
//						m.instructions.add(new InsnNode(Opcodes.LCONST_0));
//						m.instructions.add(new InsnNode(Opcodes.LRETURN));
//						break;
//					case Type.ARRAY:
//					case Type.OBJECT:
//						m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
//						m.instructions.add(new InsnNode(Opcodes.ARETURN));
//						break;
//					case Type.VOID:
//						m.instructions.add(new InsnNode(Opcodes.RETURN));
//						break;
//					}
//				}
//				m.accept(new ClassVisitor(Opcodes.ASM5, this.cv) {
//					@Override
//					public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
//						MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
//						if (name.equals("<init>")) {
//							mv = new ConstructorArgReindexer(mv, access, name, desc, oldDesc);
//						}
//						return new MethodVisitor(api, mv) {
//							@Override
//							public void visitVarInsn(int opcode, int var) {
//								super.visitVarInsn(opcode, var);
//							}
//
//							@Override
//							public void visitMethodInsn(int opcode, String owner, String name, String desc) {
//								if (!Instrumenter.isIgnoredClass(owner)) {
//									if (name.equals("<init>")) {
//										super.visitInsn(Opcodes.ACONST_NULL);
//										desc = desc.substring(0, desc.indexOf(")")) + Type.getDescriptor(UninstrumentedTaintSentinel.class) + ")" + Type.getReturnType(desc).getDescriptor();
//									} else
//										name = name + "$$INVIVO_UNINST";
//								}
//								super.visitMethodInsn(opcode, owner, name, desc);
//							}
//						};
//					}
//				});
//			}

		super.visitEnd();
	}

	private void generateNativeWrapper(MethodNode m) {
		m.access = m.access & ~Opcodes.ACC_NATIVE;
		String[] exceptions = new String[m.exceptions.size()];
		exceptions = m.exceptions.toArray(exceptions);
		Type[] argTypes = Type.getArgumentTypes(m.desc);

		boolean isPreAllocReturnType = TaintUtils.isPreAllocReturnType(m.desc);
		String newDesc = "(";
		LinkedList<LocalVariableNode> lvsToVisit = new LinkedList<LocalVariableNode>();
		LabelNode start = new LabelNode(new Label());
		LabelNode end = new LabelNode(new Label());
		for (Type t : argTypes) {
			if (t.getSort() == Type.ARRAY) {
				if (t.getElementType().getSort() != Type.OBJECT && t.getDimensions() == 1) {
					newDesc += TaintUtils.getShadowTaintType(t.getDescriptor());
				}
			} else if (t.getSort() != Type.OBJECT) {
				newDesc += Configuration.TAINT_TAG_DESC;
			}
			if (t.getSort() == Type.ARRAY && t.getElementType().getSort() != Type.OBJECT && t.getDimensions() > 1)
				newDesc += MultiDTaintedArray.getTypeForType(t).getDescriptor();
			else
				newDesc += t.getDescriptor();
		}
		Type origReturn = Type.getReturnType(m.desc);
		Type newReturn = TaintUtils.getContainerReturnType(origReturn);
		if(Configuration.IMPLICIT_TRACKING)
			newDesc += Type.getDescriptor(ControlTaintTagStack.class);
		
		if(isPreAllocReturnType) {
			newDesc += newReturn.getDescriptor();
		} else if(m.name.equals("<init>")) {
			newDesc += Type.getDescriptor(TaintSentinel.class);
		}
		newDesc += ")" + newReturn.getDescriptor();
		String newName = m.name.equals("<init>") ? m.name : m.name + TaintUtils.METHOD_SUFFIX;
		MethodVisitor mv = super.visitMethod(m.access, newName, newDesc, m.signature, exceptions);
		NeverNullArgAnalyzerAdapter an = new NeverNullArgAnalyzerAdapter(className, m.access, m.name, newDesc, mv);
		MethodVisitor soc = new SpecialOpcodeRemovingMV(an, false, className, false);
		LocalVariableManager lvs = new LocalVariableManager(m.access,newDesc, soc, an, mv);
		lvs.setPrimitiveArrayAnalyzer(new PrimitiveArrayAnalyzer(newReturn));
		GeneratorAdapter ga = new GeneratorAdapter(lvs, m.access, m.name + TaintUtils.METHOD_SUFFIX, newDesc);
		if(isInterface)
		{
			ga.visitEnd();
			return;
		}

		ga.visitCode();
		ga.visitLabel(start.getLabel());
							
		int idx = 0;
		if ((m.access & Opcodes.ACC_STATIC) == 0) {
			ga.visitVarInsn(Opcodes.ALOAD, 0);
			lvsToVisit.add(new LocalVariableNode("this", "L"+className+";", null, start, end, idx));
			idx++;
		}
		for (Type t : argTypes) {
			if (t.getSort() == Type.ARRAY) {
				if (t.getElementType().getSort() != Type.OBJECT && t.getDimensions() == 1) {
					lvsToVisit.add(new LocalVariableNode("phosphorNativeWrapArg"+idx, Configuration.TAINT_TAG_ARRAYDESC, null, start, end, idx));
					idx++;
				}
			} else if (t.getSort() != Type.OBJECT) {
				lvsToVisit.add(new LocalVariableNode("phosphorNativeWrapArg"+idx,Configuration.TAINT_TAG_DESC, null, start, end, idx));
				idx++;
			}
			ga.visitVarInsn(t.getOpcode(Opcodes.ILOAD), idx);

			lvsToVisit.add(new LocalVariableNode("phosphorNativeWrapArg"+idx, t.getDescriptor(), null, start, end, idx));
			if (t.getDescriptor().equals("Ljava/lang/Object;") || (t.getSort() == Type.ARRAY && t.getElementType().getDescriptor().equals("Ljava/lang/Object;"))) {
				//Need to make sure that it's not a boxed primitive array
				ga.visitInsn(Opcodes.DUP);
				ga.visitInsn(Opcodes.DUP);
				Label isOK = new Label();
				ga.visitTypeInsn(Opcodes.INSTANCEOF, "[" + Type.getDescriptor((!Configuration.MULTI_TAINTING ? MultiDTaintedArrayWithIntTag.class : MultiDTaintedArrayWithObjTag.class)));
				ga.visitInsn(Opcodes.SWAP);
				ga.visitTypeInsn(Opcodes.INSTANCEOF, Type.getInternalName((!Configuration.MULTI_TAINTING ? MultiDTaintedArrayWithIntTag.class : MultiDTaintedArrayWithObjTag.class)));
				ga.visitInsn(Opcodes.IOR);
				ga.visitJumpInsn(Opcodes.IFEQ, isOK);
				ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName((Configuration.MULTI_TAINTING ? MultiDTaintedArrayWithObjTag.class : MultiDTaintedArrayWithIntTag.class)), "unboxRaw", "(Ljava/lang/Object;)Ljava/lang/Object;",false);
				if(t.getSort() == Type.ARRAY)
					ga.visitTypeInsn(Opcodes.CHECKCAST, t.getInternalName());
				FrameNode fn = TaintAdapter.getCurrentFrameNode(an);
				ga.visitLabel(isOK);
				fn.accept(lvs);
			}
			else if(t.getSort() == Type.ARRAY && t.getDimensions() > 1 && t.getElementType().getSort() != Type.OBJECT)
			{
				//Need to unbox it!!
				ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName((Configuration.MULTI_TAINTING ? MultiDTaintedArrayWithObjTag.class : MultiDTaintedArrayWithIntTag.class)), "unboxRaw", "(Ljava/lang/Object;)Ljava/lang/Object;",false);
				ga.visitTypeInsn(Opcodes.CHECKCAST, t.getInternalName());
			}
			idx += t.getSize();
		}

		int opcode;
		if ((m.access & Opcodes.ACC_STATIC) == 0) {
			if ((m.access & Opcodes.ACC_PRIVATE) != 0 || m.name.equals("<init>"))
				opcode = Opcodes.INVOKESPECIAL;
			else
				opcode = Opcodes.INVOKEVIRTUAL;
		} else
			opcode = Opcodes.INVOKESTATIC;
		ga.visitMethodInsn(opcode, className, m.name, m.desc,false);
		if (origReturn != newReturn) {

			if (origReturn.getSort() == Type.ARRAY) {
				if (origReturn.getDimensions() > 1) {
					//							System.out.println(an.stack + " > " + newReturn);
					Label isOK = new Label();
					ga.visitInsn(Opcodes.DUP);
					ga.visitJumpInsn(Opcodes.IFNULL, isOK);
					ga.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
					//							//	public static Object[] initWithEmptyTaints(Object[] ar, int componentType, int dims) {
					ga.visitIntInsn(Opcodes.BIPUSH, origReturn.getElementType().getSort());
					ga.visitIntInsn(Opcodes.BIPUSH, origReturn.getDimensions());
					ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName((Configuration.MULTI_TAINTING ? MultiDTaintedArrayWithObjTag.class : MultiDTaintedArrayWithIntTag.class)), "initWithEmptyTaints", "([Ljava/lang/Object;II)Ljava/lang/Object;",false);
					FrameNode fn = TaintAdapter.getCurrentFrameNode(an);
					fn.stack.set(fn.stack.size() -1,"java/lang/Object");
					ga.visitLabel(isOK);
					fn.accept(lvs);
					ga.visitTypeInsn(Opcodes.CHECKCAST, newReturn.getDescriptor());
				} else {
					TaintAdapter.createNewTaintArray(origReturn.getDescriptor(), an, lvs, lvs);

//					//						ga.visitInsn(Opcodes.SWAP);
//					ga.visitTypeInsn(Opcodes.NEW, newReturn.getInternalName()); //T V N
//					ga.visitInsn(Opcodes.DUP_X2); //N T V N
//					ga.visitInsn(Opcodes.DUP_X2); //N N T V N
//					ga.visitInsn(Opcodes.POP); //N N T V
//					ga.visitMethodInsn(Opcodes.INVOKESPECIAL, newReturn.getInternalName(), "<init>", "([I" + origReturn.getDescriptor() + ")V");
					int retIdx = lvs.getPreAllocedReturnTypeVar(newReturn);
					an.visitVarInsn(Opcodes.ALOAD, retIdx);
					ga.visitInsn(Opcodes.SWAP);
					ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "val", origReturn.getDescriptor());
					an.visitVarInsn(Opcodes.ALOAD, retIdx);
					ga.visitInsn(Opcodes.SWAP);
					if (!Configuration.MULTI_TAINTING)
						ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "taint", "[I");
					else
						ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "taint", "[Ljava/lang/Object;");
					an.visitVarInsn(Opcodes.ALOAD, retIdx);
				}
			} else {
				//TODO here's where we store to the pre-alloc'ed container
				if(origReturn.getSize() == 1)
				{
					int retIdx = lvs.getPreAllocedReturnTypeVar(newReturn);
					an.visitVarInsn(Opcodes.ALOAD, retIdx);
					ga.visitInsn(Opcodes.SWAP);
					ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "val", origReturn.getDescriptor());
					an.visitVarInsn(Opcodes.ALOAD, retIdx);
					Configuration.taintTagFactory.generateEmptyTaint(ga);
					idx = 0;
					if ((m.access & Opcodes.ACC_STATIC) == 0) {
						idx++;
					}
					for (Type t : argTypes) {
						if (t.getSort() == Type.ARRAY) {
							if (t.getElementType().getSort() != Type.OBJECT && t.getDimensions() == 1) {
								idx++;
							}
						} else if (t.getSort() != Type.OBJECT) {
							ga.visitVarInsn(Configuration.TAINT_LOAD_OPCODE, idx);
							if(Configuration.MULTI_TAINTING)
							{
								ga.visitMethodInsn(Opcodes.INVOKESTATIC, Configuration.MULTI_TAINT_HANDLER_CLASS, "combineTags", "("+Configuration.TAINT_TAG_DESC+Configuration.TAINT_TAG_DESC+")"+Configuration.TAINT_TAG_DESC, false);
							}
							else
							{
								ga.visitInsn(Opcodes.IOR);
							}
							idx++;
						}
						idx += t.getSize();
					}
					if (!Configuration.MULTI_TAINTING)
						ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "taint", "I");
					else
						ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "taint", "Ljava/lang/Object;");
					an.visitVarInsn(Opcodes.ALOAD, retIdx);
				}
				else
				{

					int retIdx = lvs.getPreAllocedReturnTypeVar(newReturn);
					an.visitVarInsn(Opcodes.ALOAD, retIdx);
					ga.visitInsn(Opcodes.DUP_X2);
					ga.visitInsn(Opcodes.POP);
					ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "val", origReturn.getDescriptor());
					an.visitVarInsn(Opcodes.ALOAD, retIdx);
					Configuration.taintTagFactory.generateEmptyTaint(ga);
					idx = 0;
					if ((m.access & Opcodes.ACC_STATIC) == 0) {
						idx++;
					}

					//IOR all of the primitive args in too
					for (Type t : argTypes) {
						if (t.getSort() == Type.ARRAY) {
							if (t.getElementType().getSort() != Type.OBJECT && t.getDimensions() == 1) {
								idx++;
							}
						} else if (t.getSort() != Type.OBJECT) {
							ga.visitVarInsn(Configuration.TAINT_LOAD_OPCODE, idx);
							if (Configuration.MULTI_TAINTING) {
								ga.visitMethodInsn(Opcodes.INVOKESTATIC, Configuration.MULTI_TAINT_HANDLER_CLASS, "combineTags", "("+Configuration.TAINT_TAG_DESC+Configuration.TAINT_TAG_DESC+")"+Configuration.TAINT_TAG_DESC, false);
							} else {
								ga.visitInsn(Opcodes.IOR);
							}
							idx++;
						}
						idx += t.getSize();
					}

					if (!Configuration.MULTI_TAINTING)
						ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "taint", "I");
					else
						ga.visitFieldInsn(Opcodes.PUTFIELD, newReturn.getInternalName(), "taint", "Ljava/lang/Object;");
					an.visitVarInsn(Opcodes.ALOAD, retIdx);
					//					ga.visitInsn(Opcodes.ARETURN);
				}
				//				if (origReturn.getSize() == 1)
				//					ga.visitInsn(Opcodes.SWAP);
				//				else {
				//					ga.visitInsn(Opcodes.DUP_X2);
				//					ga.visitInsn(Opcodes.POP);
				//				}
//				ga.visitMethodInsn(Opcodes.INVOKESTATIC, newReturn.getInternalName(), "valueOf", "(I" + origReturn.getDescriptor() + ")" + newReturn.getDescriptor());
			}
		} else if (origReturn.getSort() != Type.VOID && (origReturn.getDescriptor().equals("Ljava/lang/Object;") || origReturn.getDescriptor().equals("[Ljava/lang/Object;"))) {
			//Check to see if the top of the stack is a primitive array, adn if so, box it.
			ga.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName((Configuration.MULTI_TAINTING ? MultiDTaintedArrayWithObjTag.class : MultiDTaintedArrayWithIntTag.class)), "boxIfNecessary", "(Ljava/lang/Object;)Ljava/lang/Object;",false);
			if (origReturn.getSort() == Type.ARRAY)
				ga.visitTypeInsn(Opcodes.CHECKCAST, "[Ljava/lang/Object;");
		}
		ga.visitLabel(end.getLabel());
		ga.returnValue();
		ga.visitMaxs(0, 0);
		if(isPreAllocReturnType)
		{
			lvsToVisit.add(new LocalVariableNode("phosphorReturnHolder", newReturn.getDescriptor(), null, start, end, lvs.getPreAllocedReturnTypeVar(newReturn)));
		}
		ga.visitEnd();
		for(LocalVariableNode n : lvsToVisit)
			n.accept(ga);		
	}

	private void generateStrLdcWrapper() {
		if (!isNormalClass)
			return;
		/*MethodVisitor mv = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, TaintUtils.STR_LDC_WRAPPER, "(Ljava/lang/String;)Ljava/lang/String;", null, null);
		mv.visitCode();
		mv.visitVarInsn(Opcodes.ALOAD, 0); //S
		mv.visitInsn(Opcodes.DUP); //S S
		mv.visitInsn(Opcodes.DUP2);

		mv.visitInsn(Opcodes.DUP);
		mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I",false);
		mv.visitInsn(Opcodes.ICONST_1);
		mv.visitInsn(Opcodes.IADD);
		if(!Configuration.MULTI_TAINTING)
			mv.visitIntInsn(Opcodes.NEWARRAY, Opcodes.T_INT);
		else
			mv.visitTypeInsn(Opcodes.ANEWARRAY, Configuration.TAINT_TAG_INTERNAL_NAME);
		mv.visitFieldInsn(Opcodes.PUTFIELD, "java/lang/String", "value" + TaintUtils.TAINT_FIELD, Configuration.TAINT_TAG_ARRAYDESC);
		{
			mv.visitInsn(Opcodes.POP);
		}
		mv.visitInsn(Opcodes.ARETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();*/
	}
}
