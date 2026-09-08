package org.fossic.ssloccn.weave;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 类内字符串常量改写器（ASM ClassVisitor，覆盖 ldc / invokedynamic / 字段 ConstantValue）。
 *
 * <p>职责：把类字节码中命中类翻译表的 String 常量替换为译文，覆盖三种形态：
 * String 型 ldc 字面量、indy bootstrap 字符串参数（Java 9+ 字符串拼接 recipe）、
 * 字段 ConstantValue 属性（编译期常量，与 ldc 一样引用 CONSTANT_String）。
 * 只换 String→String 常量，栈型与帧不变，故 {@code ClassWriter} 不需重算帧；构造时传入原
 * {@code ClassReader} 复用常量池拷贝，未触及部分字节级保留。
 *
 * <p>静态无状态，线程安全（ASM 读写器均为方法内局部对象）。
 */
public final class LdcStringRewriter {

    private LdcStringRewriter() {
    }

    /**
     * 改写结果。
     *
     * @param bytes             改写后字节码；无任何替换时为原数组（未复制）
     * @param matchedOriginals  实际命中并替换的原文集合（供调用方做未命中漂移探针）
     * @param changed           是否发生替换
     */
    public record RewriteResult(byte[] bytes, Set<String> matchedOriginals, boolean changed) {
    }

    /**
     * 改写类字节码中的字符串常量。
     *
     * @param classBytes   原类字节码
     * @param replacements 该类的 原文 → 译文 表（非空）
     * @return 改写结果
     */
    public static RewriteResult rewrite(byte[] classBytes, Map<String, String> replacements) {
        if (replacements.isEmpty()) {
            return new RewriteResult(classBytes, Set.of(), false);
        }
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        Set<String> matched = new HashSet<>();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                // 字段 ConstantValue：编译期字符串常量（如 static final String X = "..."）
                // 直接落在 CONSTANT_String 上，不经 ldc，必须在此替换
                if (value instanceof String string) {
                    String translation = replacements.get(string);
                    if (translation != null) {
                        matched.add(string);
                        return super.visitField(access, name, descriptor, signature, translation);
                    }
                }
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor,
                        signature, exceptions)) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String string) {
                            String translation = replacements.get(string);
                            if (translation != null) {
                                matched.add(string);
                                super.visitLdcInsn(translation);
                                return;
                            }
                        }
                        super.visitLdcInsn(value);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor,
                                                       Handle bootstrapMethod,
                                                       Object... bootstrapMethodArguments) {
                        Object[] newArgs = bootstrapMethodArguments.clone();
                        for (int i = 0; i < newArgs.length; i++) {
                            if (newArgs[i] instanceof String string) {
                                String translation = replacements.get(string);
                                if (translation != null) {
                                    matched.add(string);
                                    newArgs[i] = translation;
                                }
                            }
                        }
                        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethod, newArgs);
                    }
                };
            }
        }, 0);

        if (matched.isEmpty()) {
            return new RewriteResult(classBytes, matched, false);
        }
        return new RewriteResult(writer.toByteArray(), matched, true);
    }
}
