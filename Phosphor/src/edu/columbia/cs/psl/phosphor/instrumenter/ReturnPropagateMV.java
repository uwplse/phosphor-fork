package edu.columbia.cs.psl.phosphor.instrumenter;


import java.util.Arrays;

import edu.columbia.cs.psl.phosphor.Instrumenter;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.Label;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.MethodVisitor;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.Opcodes;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.Type;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.commons.AnalyzerAdapter;
import edu.columbia.cs.psl.phosphor.runtime.TaintSentinel;

public class ReturnPropagateMV extends MethodVisitor implements Opcodes {
	
	private int foundOpcode = -1;
	private Label endLabel = null;
	private Type returnType;
	private boolean ignoreFrames;
	private Type[] argumentTypes;
	private Type ownerType;
	private boolean isStatic;
	private boolean isWrapper;
	
	protected AnalyzerAdapter aa;
	
	private static Class<?>[] wrapperClasses = new Class<?>[] {
		Integer.class,
		Double.class,
		Byte.class,
		Boolean.class,
		Short.class,
		Float.class,
		Character.class,
		Long.class
	};

	public ReturnPropagateMV(MethodVisitor mv, boolean ignoreFrames, int access,
			String className, String methodDesc) {
		super(ASM5, mv);
		this.returnType = Type.getReturnType(methodDesc);
		this.ownerType = Type.getObjectType(className);
		this.argumentTypes = Type.getArgumentTypes(methodDesc);
		this.ignoreFrames = ignoreFrames;
		this.isStatic = (access & ACC_STATIC) != 0;
		this.isWrapper = false;
		for(Class<?> kls : wrapperClasses) {
			if(className.equals(Type.getInternalName(kls))) {
				isWrapper = true;
				break;
			}
		}
	}


	@Override
	public void visitInsn(int opcode) {
		if(opcode <= RETURN && opcode >= IRETURN) {
			if(foundOpcode != -1) {
				assert foundOpcode == opcode;
			} else {
				foundOpcode = opcode;
			}
			if(endLabel == null) {
				endLabel = new Label();
			}
			if(aa.stack != null && aa.stack.size() > 1) {
				int top = aa.stack.size() - 2;
				while(top >= 0) {
					if(aa.stack.get(top) == Opcodes.TOP) {
						assert aa.stack.get(top - 1) == Opcodes.DOUBLE || aa.stack.get(top - 1) == Opcodes.LONG;
						super.visitInsn(DUP_X2);
						super.visitInsn(POP);
						super.visitInsn(POP2);
						top -= 2;
					} else {
						super.visitInsn(SWAP);
						super.visitInsn(POP);
						top -= 1;
					}
				}
			}
			super.visitJumpInsn(GOTO, endLabel);
			return;
		} else {
			super.visitInsn(opcode);
		}
	}
	
	private static Object opcodeOfType(Type t) {
		Object toReturn = null;
		switch(t.getSort()) {
		case Type.OBJECT:
			toReturn = t.getInternalName();
			break;
		case Type.ARRAY:
			toReturn = t.getDescriptor();
			break;
		case Type.BYTE:
		case Type.INT:
		case Type.BOOLEAN:
		case Type.SHORT:
		case Type.CHAR:
			toReturn = Opcodes.INTEGER;
			break;
		case Type.FLOAT:
			toReturn = Opcodes.FLOAT;
			break;
		case Type.DOUBLE:
			toReturn = Opcodes.DOUBLE;
			break;
		case Type.LONG:
			toReturn = Opcodes.LONG;
			break;
		}
		return toReturn;
	}
	
	private boolean propagateReceiver() {
		return !isStatic &&
			!Instrumenter.isIgnoredClass(ownerType.getInternalName()) &&
			!isWrapper;
	}
	
	private static boolean propagateType(Type t) {
		return t.getSort() == Type.OBJECT &&
			(!Instrumenter.isIgnoredClass(t.getInternalName()) ||
				t.getInternalName().startsWith("edu/columbia/cs/psl/phosphor/struct/Tainted"));
	}
	
	private boolean shouldMultiPropagate() {
		return propagateReceiver() && propagateType(returnType);
	}



	private void pushInt(int v) {
		switch(v) {
		case 0:
			super.visitInsn(ICONST_0);
			break;
		case 1:
			super.visitInsn(ICONST_1);
			break;
		case 2:
			super.visitInsn(ICONST_2);
			break;
		case 3:
			super.visitInsn(ICONST_3);
			break;
		case 4:
			super.visitInsn(ICONST_4);
			break;
		case 5:
			super.visitInsn(ICONST_5);
			break;
		default:
			if(v < Byte.MAX_VALUE) {
				super.visitIntInsn(BIPUSH, v);
			} else if(v < Short.MAX_VALUE) {
				super.visitIntInsn(SIPUSH, v);
			} else {
				super.visitLdcInsn(new Integer(v));
			}
		}
	}
	
	protected void loadTaintedArgArray(int[] taintArgs) {
		int numArgs = 0;
		for(int i = 0; i < taintArgs.length; i++) {
			if(taintArgs[i] != -1) {
				numArgs++;
			}
		}
		assert numArgs > 0;
		pushInt(numArgs);
		super.visitTypeInsn(ANEWARRAY, Type.getInternalName(Object.class));
		for(int i = 0; i < taintArgs.length; i++) {
			if(taintArgs[i] == -1) {
				break;
			}
			super.visitInsn(DUP);
			this.pushInt(i);
			visitVarInsn(ALOAD, taintArgs[i]);
			super.visitInsn(AASTORE);
		}
	}
	
	@Override
	public void visitMaxs(int a, int b) {
		if(foundOpcode == -1) {
			assert endLabel == null;
			super.visitMaxs(a, b);
			return;
		}
//		Object stackType = opcodeOfType(returnType);
		super.visitLabel(endLabel);
		if(!ignoreFrames) {
			int nLocals = argumentTypes.length;
			if(!isStatic) {
				nLocals++;
			}
			boolean skipSentinel = argumentTypes.length > 0 
					&& argumentTypes[argumentTypes.length - 1].getSort() == Type.OBJECT &&
				argumentTypes[argumentTypes.length - 1].getInternalName().equals(Type.getInternalName(TaintSentinel.class));
			if(skipSentinel) {
				nLocals--;
			}
			Object[] localTypes = new Object[nLocals];
			int offs = 0;
			if(!isStatic) {
				localTypes[0] = opcodeOfType(ownerType);
				offs = 1;
			}
			for(int i = 0; i < argumentTypes.length; i++) {
				if(i == argumentTypes.length - 1 && skipSentinel) {
					continue;
				}
				localTypes[i + offs] = opcodeOfType(argumentTypes[i]);
			}
			
			int nStack = 0;
			Object[] stackTypes = null;
			if(returnType.getSort() != Type.VOID) {
				nStack = 1;
				stackTypes = new Object[]{opcodeOfType(returnType)};
			}
			super.visitFrame(F_NEW, nLocals, localTypes, nStack, stackTypes);
		}
		
		int[] taintArgs = computeTaintArgs();
		if(taintArgs.length == 0 || taintArgs[0] == -1) {
			super.visitInsn(foundOpcode);
			super.visitMaxs(a, b);
			return;
		}
		if(shouldMultiPropagate()) {
			super.visitVarInsn(ALOAD, 0);
			loadTaintedArgArray(taintArgs);
			super.visitMethodInsn(INVOKESTATIC, "edu/washington/cse/instrumentation/runtime/TaintPropagation",
				"propagateMultiTaint", "(Ljava/lang/Object;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
			super.visitTypeInsn(CHECKCAST, returnType.getInternalName());
		} else if(propagateReceiver()) {
			super.visitVarInsn(ALOAD, 0);
			loadTaintedArgArray(taintArgs);
			super.visitMethodInsn(INVOKESTATIC, "edu/washington/cse/instrumentation/runtime/TaintPropagation",
				"propagateTaint", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
			super.visitInsn(POP);
		} else if(propagateType(returnType)) {
			loadTaintedArgArray(taintArgs);
			super.visitMethodInsn(INVOKESTATIC, "edu/washington/cse/instrumentation/runtime/TaintPropagation",
				"propagateTaint", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false);
			super.visitTypeInsn(CHECKCAST, returnType.getInternalName());
		}
		super.visitInsn(foundOpcode);
		super.visitMaxs(a + 5, b);
	}


	private int[] computeTaintArgs() {
		int nArgs = argumentTypes.length + (isStatic ? 0 : 1);
		int[] toRet = new int[nArgs];
		Arrays.fill(toRet, -1);
		int offs = 0;
		int argInd = 0;
		if(!isStatic) {
			if(propagateReceiver()) {
				toRet[argInd++] = 0;
			}
			offs = 1;
		}
		for(int i = 0; i < argumentTypes.length; i++) {
			if(propagateType(argumentTypes[i])) {
				toRet[argInd++] = offs;
			}
			offs += argumentTypes[i].getSize();
		}
		return toRet;
	}

}
