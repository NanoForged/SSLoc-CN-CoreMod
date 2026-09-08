package org.fossic.ssloccn.tablegen;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 测试夹具：现场织入含已知常量池布局的类字节码。
 *
 * <p>用于审计/改写测试：生成的类包含 String ldc、非 String ldc（int/long/double/Class）
 * 与 invokedynamic 字符串 bootstrap 参数，覆盖常量池扫描的全部口径。
 */
final class TestClasses {

    /** StringConcatFactory.makeConcatWithConstants 的 bootstrap 句柄（真实签名）。 */
    private static final Handle CONCAT_BSM = new Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/StringConcatFactory",
            "makeConcatWithConstants",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
            false);

    private TestClasses() {
    }

    /**
     * 织入一个类：静态方法 m() 依次 ldc 各值并弹栈，随后可选执行一条
     * 带字符串 bootstrap 参数的 invokedynamic 并弹栈。
     *
     * @param internalName 类内部名（{@code /} 分隔）
     * @param ldcValues    ldc 常量（支持 String/Integer/Long/Double/org.objectweb.asm.Type）
     * @param indyArgs     indy bootstrap 参数；空数组表示不生成 indy
     */
    static byte[] buildClass(String internalName, Object[] ldcValues, Object[] indyArgs) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                internalName, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()V", null, null);
        mv.visitCode();
        for (Object value : ldcValues) {
            mv.visitLdcInsn(value);
            if (value instanceof Long || value instanceof Double) {
                mv.visitInsn(Opcodes.POP2);
            } else {
                mv.visitInsn(Opcodes.POP);
            }
        }
        if (indyArgs.length > 0) {
            mv.visitInvokeDynamicInsn("makeConcat", "()Ljava/lang/String;", CONCAT_BSM, indyArgs);
            mv.visitInsn(Opcodes.POP);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /**
     * 织入 extra_ref 场景：m() 先 ldc 字符串 {@code value} 并弹栈，随后 invokestatic
     * 调用一个名为 {@code value} 的方法——使该 UTF8 同时被 CONSTANT_String 与
     * NameAndType（符号引用）引用（对应 jar_loader 拒翻的反射危险场景）。
     */
    static byte[] buildClassWithExtraRef(String internalName, String value) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                internalName, null, "java/lang/Object", null);
        MethodVisitor mv = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "m", "()V", null, null);
        mv.visitCode();
        mv.visitLdcInsn(value);
        mv.visitInsn(Opcodes.POP);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, internalName, value, "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
