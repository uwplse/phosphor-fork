package edu.columbia.cs.psl.phosphor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.List;

import edu.columbia.cs.psl.phosphor.instrumenter.TaintTrackingClassVisitor;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.ClassReader;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.ClassVisitor;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.ClassWriter;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.MethodVisitor;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.Opcodes;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.Type;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.commons.JSRInlinerAdapter;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.commons.SerialVersionUIDAdder;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.AnnotationNode;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.ClassNode;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.FieldNode;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.tree.MethodNode;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.util.CheckClassAdapter;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.util.TraceClassVisitor;
import edu.columbia.cs.psl.phosphor.runtime.Taint;
import edu.columbia.cs.psl.phosphor.runtime.TaintInstrumented;
import edu.columbia.cs.psl.phosphor.struct.ControlTaintTagStack;
import edu.columbia.cs.psl.phosphor.struct.Tainted;
import edu.columbia.cs.psl.phosphor.struct.TaintedByteArrayWithIntTag;
import edu.columbia.cs.psl.phosphor.struct.TaintedByteArrayWithObjTag;
import edu.columbia.cs.psl.phosphor.struct.TaintedWithIntTag;
import edu.columbia.cs.psl.phosphor.struct.TaintedWithObjTag;

public class PreMain {
    private static Instrumentation instrumentation;

    static final boolean DEBUG = false;

	public static ClassLoader bigLoader = PreMain.class.getClassLoader();
	public static final class PCLoggingTransformer implements ClassFileTransformer {
		private final class HackyClassWriter extends ClassWriter {
			
			private HackyClassWriter(ClassReader classReader, int flags) {
				super(classReader, flags);
			}

			private Class<?> getClass(String name) throws ClassNotFoundException
			{
				try {
					return Class.forName(name.replace("/", "."),false,bigLoader);
				} catch (SecurityException e) {
					throw new ClassNotFoundException("Security exception when loading class");
				} catch(NoClassDefFoundError e)
				{
					throw new ClassNotFoundException();
				}
				catch(Throwable e)
				{
					throw new ClassNotFoundException(); 
				}
			}
			protected String getCommonSuperClass(String type1, String type2) {
				Class<?> c, d;
				try {
					c = getClass(type1);
					d = getClass(type2);
				} catch (ClassNotFoundException e) {
//					System.err.println("Can not do superclass for " + type1 + " and " + type2);
					//					        	logger.debug("Error while finding common super class for " + type1 +"; " + type2,e);
					return "java/lang/Object";
					//					        	throw new RuntimeException(e);
				} catch (ClassCircularityError e) {
					return "java/lang/Object";
				}
				if (c.isAssignableFrom(d)) {
					return type1;
				}
				if (d.isAssignableFrom(c)) {
					return type2;
				}
				if (c.isInterface() || d.isInterface()) {
					return "java/lang/Object";
				} else {
					do {
						c = c.getSuperclass();
					} while (!c.isAssignableFrom(d));
//					System.out.println("Returning " + c.getName());
					return c.getName().replace('.', '/');
				}
			}
		}
		

		static boolean innerException = false;

		public TaintedByteArrayWithObjTag transform$$PHOSPHORTAGGED(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, Taint[] classtaint,
				byte[] classfileBuffer, TaintedByteArrayWithObjTag ret) throws IllegalClassFormatException {
			Configuration.IMPLICIT_TRACKING = false;
			Configuration.MULTI_TAINTING = true;
			Configuration.init();
			ret.val = transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
			ret.taint = null;
			return ret;
		}
		public TaintedByteArrayWithObjTag transform$$PHOSPHORTAGGED(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, Taint[] classtaint,
				byte[] classfileBuffer, ControlTaintTagStack ctrl, TaintedByteArrayWithObjTag ret) throws IllegalClassFormatException {
			Configuration.IMPLICIT_TRACKING = true;
			Configuration.MULTI_TAINTING = true;
			Configuration.init();
			ret.val = transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
			ret.taint = null;
			return ret;
		}
		public TaintedByteArrayWithIntTag transform$$PHOSPHORTAGGED(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, int[] classtaint, byte[] classfileBuffer, TaintedByteArrayWithIntTag ret) throws IllegalClassFormatException
		{
			Configuration.IMPLICIT_TRACKING = false;
			Configuration.MULTI_TAINTING = false;
			Configuration.init();
	        bigLoader = loader;
	        Instrumenter.loader = bigLoader;
			if(className.startsWith("sun")) //there are dynamically generated accessors for reflection, we don't want to instrument those.
				ret.val = classfileBuffer;
			else
				ret.val = transform(loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
			ret.taint = new int[ret.val.length];
			return ret;
		}
		
		private static void dumpClass(String className, byte[] buffer) throws IOException {
			if (DEBUG) {
				File debugDir = new File("/tmp/debug");
				if (!debugDir.exists())
					debugDir.mkdir();
				File f = new File("/tmp/debug/" + className.replace("/", ".") + ".class");
				System.out.println(f.getAbsolutePath());
				try(FileOutputStream fos = new FileOutputStream(f)) {
					fos.write(buffer);
				}
			}
		}
		
		public static boolean didIt = false;
		
		public byte[] transform(ClassLoader loader, final String className2, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {		
			ClassReader cr = new ClassReader(classfileBuffer);
			
			String className = cr.getClassName();
			innerException = false;

			if(Instrumenter.isIgnoredClass(className))
			{
				System.out.println("Premain.java ignore: " + className);
				return classfileBuffer;
			}
//			if(className.equals("java/lang/Integer"))
//				System.out.println(className);
			ClassNode cn = new ClassNode();
			cr.accept(cn, ClassReader.SKIP_CODE);
			boolean skipFrames = false;
			if (cn.version >= 100 || cn.version <= 50 || className.endsWith("$Access4JacksonSerializer")
					|| className.endsWith("$Access4JacksonDeSerializer"))
				skipFrames = true;
			if (cn.visibleAnnotations != null)
				for (AnnotationNode an : cn.visibleAnnotations) {
					if (an.desc.equals(Type.getDescriptor(TaintInstrumented.class))) {
//						System.out.println("Found annotation for: "  + className2);
						return classfileBuffer;
					}
				}
			if(cn.interfaces != null)
				for(String s : cn.interfaces)
				{
					if(s.equals(Type.getInternalName(TaintedWithObjTag.class)) || s.equals(Type.getInternalName(TaintedWithIntTag.class))) {
						System.out.println("Skipping instrumentation because we found interface for: "  + className2);
						return classfileBuffer;
					}
				}
			for(MethodNode mn : cn.methods)
				if(mn.name.equals("getPHOSPHOR_TAG")) {
					System.out.println("Skipping instrumentation because we found get* method for: " + className2);
					return classfileBuffer;
				}
			List<FieldNode> fields = cn.fields;
			if (skipFrames)
			{
				cn = null;
				//This class is old enough to not guarantee frames. Generate new frames for analysis reasons, then make sure to not emit ANY frames.
				ClassWriter cw = new HackyClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
				cr.accept(new ClassVisitor(Opcodes.ASM5, cw) {
					@Override
					public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
						return new JSRInlinerAdapter(super.visitMethod(access, name, desc, signature, exceptions), access, name, desc, signature, exceptions);
					}
				}, 0);
				cr = new ClassReader(fixupFrames(cw.toByteArray()));
			}
//			System.out.println("Instrumenting: " + className);
//			System.out.println(classBeingRedefined);
			//Find out if this class already has frames
			TraceClassVisitor cv =null;
			try {
				
				ClassWriter cw = new HackyClassWriter(cr, ClassWriter.COMPUTE_MAXS);
				cr.accept(
				//							new CheckClassAdapter(
						new SerialVersionUIDAdder(new TaintTrackingClassVisitor(cw, skipFrames, fields))
						//									)
						, ClassReader.EXPAND_FRAMES);
					dumpClass(className, cw.toByteArray());
				{
//					if(TaintUtils.DEBUG_FRAMES)
//						System.out.println("NOW IN CHECKCLASSADAPTOR");
					if (TaintUtils.VERIFY_CLASS_GENERATION && !className.startsWith("org/codehaus/janino/UnitCompiler") &&
							!className.startsWith("jersey/repackaged/com/google/common/cache/LocalCache") && !className.startsWith("jersey/repackaged/com/google/common/collect/AbstractMapBasedMultimap")
							&& !className.startsWith("jersey/repackaged/com/google/common/collect/")) {
						cr = new ClassReader(cw.toByteArray());
						cr.accept(new CheckClassAdapter(new ClassWriter(0)), 0);
					}
				}
//				cv= new TraceClassVisitor(null,null);
//				try{
//					System.err.println("WARN LOGGING CLASS TO ASCII");
//					cr = new ClassReader(cw.toByteArray());
//					cr.accept(
////							new CheckClassAdapter(
//									cv	
////									)
//							, 0);
//					PrintWriter pw = null;
//					try {
//						pw = new PrintWriter(new FileWriter("lastClass.txt"));
//					} catch (IOException e) {
//						// TODO Auto-generated catch block
//						e.printStackTrace();
//					}
//					cv.p.print(pw);
//					pw.flush();
//				}
//				catch(Throwable t)
//				{
//					t.printStackTrace();
//				}
//				System.out.println("Succeeded w " + className);
				byte[] toRet = cw.toByteArray();
				return toRet;
			} catch (Throwable ex) {
				ex.printStackTrace();
				cv= new TraceClassVisitor(null,null);
				try{
					cr.accept(
//							new CheckClassAdapter(
									new SerialVersionUIDAdder(new TaintTrackingClassVisitor(cv,skipFrames, fields))
//									)
							, ClassReader.EXPAND_FRAMES);
				}
				catch(Throwable ex2)
				{				}
				ex.printStackTrace();
				System.err.println("method so far:");
				if (!innerException) {
					PrintWriter pw = null;
					try {
						pw = new PrintWriter(new FileWriter("/tmp/lastClass.txt"));
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					cv.p.print(pw);
					pw.flush();
				}
				System.out.println("Saving " + className);
					File f = new File("/tmp/debug/"+className.replace("/", ".")+".class");
					try{
					FileOutputStream fos = new FileOutputStream(f);
					fos.write(classfileBuffer);
					fos.close();
					}
					catch(Exception ex2)
					{
						ex.printStackTrace();
					}
					System.exit(-1);
					return new byte[0];

			}
		}
		private byte[] fixupFrames(byte[] byteArray) {
			ClassReader cr = new ClassReader(byteArray);
			ClassWriter cw = new ClassWriter(cr, 0);
			ClassVisitor cv = new ClassVisitor(Opcodes.ASM5, cw) {
				@Override
				public MethodVisitor visitMethod(int access, String name, final String desc,
						String signature, String[] exceptions) {
					MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
					final boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
					final int argOffset = isStatic ? 0 : 1;
					final Type[] argTypes = Type.getArgumentTypes(desc);
					final int numArgs = argTypes.length; 
					return new MethodVisitor(Opcodes.ASM5, mv) {
						@Override
						public void visitFrame(int type, int nLocal, Object[] local,
								int nStack, Object[] stack) {
							for(int i = 0; i < numArgs; i++) {
								if(local[i + argOffset] instanceof String) {
									if(argTypes[i].getSort() == Type.OBJECT && argTypes[i].getClassName().equals("java.lang.Object")) {
										local[i + argOffset] = argTypes[i].getInternalName();
									}
								}
							}
							super.visitFrame(type, nLocal, local, nStack, stack);
						}
					};
				}
			};
			cr.accept(cv, ClassReader.EXPAND_FRAMES);
			return cw.toByteArray();
		}
	}
	
	public static void premain$$PHOSPHORTAGGED(String args, Instrumentation inst, ControlTaintTagStack ctrl) {
		Configuration.IMPLICIT_TRACKING = true;
		Configuration.MULTI_TAINTING = true;
		Configuration.init();
		premain(args, inst);
	}
	public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
        if(Instrumenter.loader == null)
        	Instrumenter.loader = bigLoader;
		ClassFileTransformer transformer = new PCLoggingTransformer();
		inst.addTransformer(transformer);

	}
}
