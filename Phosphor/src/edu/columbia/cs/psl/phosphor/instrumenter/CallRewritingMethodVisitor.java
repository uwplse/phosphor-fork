package edu.columbia.cs.psl.phosphor.instrumenter;

import edu.columbia.cs.psl.phosphor.Configuration;
import edu.columbia.cs.psl.phosphor.Instrumenter;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.MethodVisitor;
import edu.columbia.cs.psl.phosphor.org.objectweb.asm.Opcodes;

public class CallRewritingMethodVisitor extends MethodVisitor {
	public CallRewritingMethodVisitor(String className, int access, String name,
			String desc, String signature, String[] exceptions, MethodVisitor mar) {
		super(Opcodes.ASM5, mar);
	}

	@Override
	public void visitMethodInsn(int opcode, String owner, String name,
			String desc, boolean itf) {
		if(opcode != Opcodes.INVOKESTATIC) {
			super.visitMethodInsn(opcode, owner, name, desc, itf);
			return;
		}
		if(!Instrumenter.isIgnoredClass(owner) && !Instrumenter.isIgnoredMethod(owner, name, desc)) {
			super.visitMethodInsn(opcode, owner, name, desc, itf);
			return;
		}
		Configuration.taintTagFactory.handleIgnoredStaticCall(owner, name, desc, this.mv);
	}
	
}
