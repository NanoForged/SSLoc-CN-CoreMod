package org.fossic.ssloccn;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试夹具：现场织入/回读含已知常量布局的类字节码。
 *
 * <p>覆盖 String ldc、非 String ldc（int/long/double/Class）与 invokedynamic
 * 字符串 bootstrap 参数三种形态，供改写器与 transformer 测试做真实字节码验证。
 */
public final class TestClasses {

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
    public static byte[] buildClass(String internalName, Object[] ldcValues, Object[] indyArgs) {
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
     * 回读类字节码中出现的全部 String 型 ldc 值与 indy bootstrap 字符串参数（按出现序）。
     */
    public static List<String> collectStringConstants(byte[] classBytes) {
        List<String> strings = new ArrayList<>();
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String string) {
                            strings.add(string);
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       Handle bootstrapMethod,
                                                       Object... bootstrapMethodArguments) {
                        for (Object arg : bootstrapMethodArguments) {
                            if (arg instanceof String string) {
                                strings.add(string);
                            }
                        }
                    }
                };
            }
        }, 0);
        return strings;
    }
}
