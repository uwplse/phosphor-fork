package edu.columbia.cs.psl.phosphor.instrumenter;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import edu.columbia.cs.psl.phosphor.Configuration;
import edu.columbia.cs.psl.phosphor.TaintUtils;
import edu.columbia.cs.psl.phosphor.runtime.BoxedPrimitiveStoreWithObjTags;
import edu.columbia.cs.psl.phosphor.runtime.HardcodedBypassStore;

public class TaintTagFieldCastMV extends MethodVisitor implements Opcodes {

	public TaintTagFieldCastMV(MethodVisitor mv) {
		super(Opcodes.ASM5, mv);
	}

	@Override
	public void visitFieldInsn(int opcode, String owner, String name, String desc) {
		if(opcode == Opcodes.GETFIELD && !TaintAdapter.canRawTaintAccess(owner) && !owner.equals("java/lang/StackTraceElement") && name.equals("value" + TaintUtils.TAINT_FIELD)) {
			assert owner.equals("java/lang/Float") ||	owner.equals("java/lang/Double") ||owner.equals("java/lang/Integer") ||	owner.equals("java/lang/Long");
			super.visitInsn(DUP); // O O
			super.visitFieldInsn(opcode, owner, name, "I"); // I O 
			super.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(BoxedPrimitiveStoreWithObjTags.class), "getTaint", "(Ljava/lang/Object;I)Ljava/lang/Object;", false); // O
			super.visitTypeInsn(CHECKCAST, Configuration.TAINT_TAG_INTERNAL_NAME); // T
		} else if(opcode == PUTFIELD && !TaintAdapter.canRawTaintAccess(owner) && !owner.equals("java/lang/StackTraceElement") && name.equals("value" + TaintUtils.TAINT_FIELD)) {
			assert owner.equals("java/lang/Float") ||	owner.equals("java/lang/Double") ||owner.equals("java/lang/Integer") ||	owner.equals("java/lang/Long");
			super.visitInsn(SWAP); // O T
			super.visitInsn(DUP_X1); // O T O
			super.visitInsn(SWAP); // T O O
			super.visitMethodInsn(INVOKESTATIC, Type.getInternalName(BoxedPrimitiveStoreWithObjTags.class), "putTaint", "(Ljava/lang/Object;Ljava/lang/Object;)I", false); // I O
			super.visitFieldInsn(opcode, owner, name, "I");
		} else if ((opcode == Opcodes.GETFIELD/* || opcode == GETSTATIC*/) && !TaintAdapter.canRawTaintAccess(owner) && name.endsWith(TaintUtils.TAINT_FIELD)
				&& (desc.equals(Configuration.TAINT_TAG_DESC) || desc.equals(Configuration.TAINT_TAG_ARRAYDESC))) {
			if (desc.equals(Configuration.TAINT_TAG_DESC)) {
				super.visitFieldInsn(opcode, owner, name, "I");
				super.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(HardcodedBypassStore.class), "get", "(I)Ljava/lang/Object;", false);
				super.visitTypeInsn(CHECKCAST, Configuration.TAINT_TAG_INTERNAL_NAME);
			} else {
				super.visitFieldInsn(opcode, owner, name, "[I");
				super.visitMethodInsn(Opcodes.INVOKESTATIC, Type.getInternalName(HardcodedBypassStore.class), "get", "([I)[Ljava/lang/Object;", false);
				super.visitTypeInsn(CHECKCAST, Configuration.TAINT_TAG_ARRAY_INTERNAL_NAME);
			}
		} else if ((opcode == Opcodes.PUTFIELD /*|| opcode == PUTSTATIC*/) &&	!TaintAdapter.canRawTaintAccess(owner) && name.endsWith(TaintUtils.TAINT_FIELD) &&
			(desc.equals(Configuration.TAINT_TAG_DESC) || desc.equals("[" + Configuration.TAINT_TAG_DESC))) {
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
