package edu.columbia.cs.psl.phosphor.instrumenter;

import edu.columbia.cs.psl.phosphor.Configuration;
import edu.columbia.cs.psl.phosphor.TaintUtils;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.MethodVisitor;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.Opcodes;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.Type;
import edu.columbia.cs.psl.phosphor.runtime.BoxedPrimitiveStoreWithObjTags;
import edu.columbia.cs.psl.phosphor.runtime.HardcodedBypassStore;

public class TaintTagFieldCastMV extends MethodVisitor implements Opcodes {

	public TaintTagFieldCastMV(MethodVisitor mv) {
		super(Opcodes.ASM5, mv);
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
		if ((opcode == Opcodes.GETFIELD) && !TaintAdapter.canRawTaintAccess(owner) && name.endsWith(TaintUtils.TAINT_FIELD)
				&& (desc.equals(Configuration.TAINT_TAG_DESC) || desc.equals(Configuration.TAINT_TAG_ARRAYDESC))) {
			if(!owner.equals("java/lang/StackTraceElement") && name.equals("value" + TaintUtils.TAINT_FIELD)) {
				assert owner.equals("java/lang/Float") ||	owner.equals("java/lang/Double") ||owner.equals("java/lang/Integer") ||	owner.equals("java/lang/Long");
				super.visitInsn(DUP);
				super.visitFieldInsn(opcode, owner, name, "I");
				super.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(BoxedPrimitiveStoreWithObjTags.class), "getTaint", "(Ljava/lang/Object;I)Ljava/lang/Object;", false);
				super.visitTypeInsn(CHECKCAST, Configuration.TAINT_TAG_INTERNAL_NAME);
				return;
			}
			if (desc.equals(Configuration.TAINT_TAG_DESC)) {
				super.visitFieldInsn(opcode, owner, name, "I");
				super.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(HardcodedBypassStore.class), "get", "(I)Ljava/lang/Object;", false);
				super.visitTypeInsn(CHECKCAST, Configuration.TAINT_TAG_INTERNAL_NAME);
			} else {
				super.visitFieldInsn(opcode, owner, name, "[I");
				super.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(HardcodedBypassStore.class), "get", "([I)[Ljava/lang/Object;", false);
				super.visitTypeInsn(CHECKCAST, Configuration.TAINT_TAG_ARRAY_INTERNAL_NAME);
			}
		} else if (
			opcode == Opcodes.PUTFIELD && 
			!TaintAdapter.canRawTaintAccess(owner) && name.endsWith(TaintUtils.TAINT_FIELD) &&
			(desc.equals(Configuration.TAINT_TAG_DESC) || desc.equals("[" + Configuration.TAINT_TAG_DESC))) {
			if(!owner.equals("java/lang/StackTraceElement") && name.equals("value" + TaintUtils.TAINT_FIELD)) {
				assert owner.equals("java/lang/Float") ||	owner.equals("java/lang/Double") ||owner.equals("java/lang/Integer") ||	owner.equals("java/lang/Long");
				super.visitMethodInsn(INVOKESTATIC, Type.getInternalName(BoxedPrimitiveStoreWithObjTags.class), "putTaint", "(Ljava/lang/Object;Ljava/lang/Object;)I", false);
				super.visitFieldInsn(opcode, owner, name, "I");
				return;
			}
			if (desc.equals(Configuration.TAINT_TAG_DESC)) {
				super.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(HardcodedBypassStore.class), "add", "(Ljava/lang/Object;)I", false);
				super.visitFieldInsn(opcode, owner, name, "I");
			} else {
				super.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(HardcodedBypassStore.class), "add", "([Ljava/lang/Object;)[I", false);
				super.visitFieldInsn(opcode, owner, name, "[I");
			}
		} else {
			super.visitFieldInsn(opcode, owner, name, desc);
		}
	}
}
