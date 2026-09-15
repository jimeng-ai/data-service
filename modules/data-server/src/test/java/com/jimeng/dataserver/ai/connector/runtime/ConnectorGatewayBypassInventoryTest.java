package com.jimeng.dataserver.ai.connector.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「所有对客户系统的访问必经网关」的例外清单，从<b>编译产物</b>里钉住。
 *
 * <p>网关、健康探测、接入探测三处的注释描述的是同一个事实：本进程里谁会自己调 {@code Connector.open(...)}。
 * 注释写错不报错——上一轮就是这样：三处注释都说 ping 是唯一的例外，而接入探测一直在旁边直接连客户库，
 * 发着一条写尝试，没有审计、没有限流。所以把这个事实变成一条会红的测试：
 * 扫 {@code target/classes} 里每一个方法体，找出所有对 {@code open(ConnectorInstance)} 的调用，调用方必须恰好是这三个类。
 *
 * <p>扫字节码而不是源码：源码 grep 认不出换了变量名的调用，也会被注释里的字样误伤。
 * 方法引用（{@code connector::open}）编译成 invokedynamic，一并认。
 * 跳过桥接方法：连接器实现把返回类型收窄时，编译器生成的桥接方法会调用真正的 {@code open}——那是实现自己，不是调用方。
 */
class ConnectorGatewayBypassInventoryTest {

    private static final String OPEN_DESCRIPTOR_PREFIX = "(Lcom/jimeng/dataserver/ai/connector/spi/ConnectorInstance;)";

    /** 网关本身，加上两个有文档的例外。改这份清单之前，先去网关类注释「两个有文档的例外」那一节写清楚理由。 */
    private static final Set<String> DOCUMENTED = Set.of(
            ConnectorGateway.class.getName(),
            ConnectorHealthJob.class.getName(),
            ConnectorProbeService.class.getName());

    @Test
    @DisplayName("★ 自己打开客户系统会话的，只有网关和两个有文档的例外（健康探测、接入探测）")
    void 只有网关和两个有文档的例外会打开会话() throws Exception {
        Path classes = Path.of(ConnectorGateway.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "应当从编译输出目录加载主代码，实际: " + classes);

        List<Path> files;
        try (Stream<Path> walk = Files.walk(classes)) {
            files = walk.filter(p -> p.toString().endsWith(".class")).toList();
        }
        Set<String> callers = new TreeSet<>();
        for (Path file : files) {
            collectOpenCallers(Files.readAllBytes(file), callers);
        }

        assertEquals(new TreeSet<>(DOCUMENTED), callers,
                "调用 Connector.open 的类与有文档的例外清单对不上。多出来的那个要么改走 ConnectorGateway，"
                        + "要么把理由写进网关类注释「两个有文档的例外」并更新这份清单；少了的说明清单或注释过期了。");
    }

    private static void collectOpenCallers(byte[] bytes, Set<String> callers) {
        ClassReader reader = new ClassReader(bytes);
        String owner = reader.getClassName();
        reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                if ((access & Opcodes.ACC_BRIDGE) != 0) {
                    return null;
                }
                return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public void visitMethodInsn(int opcode, String insnOwner, String insnName, String insnDescriptor,
                                                boolean isInterface) {
                        if (isOpen(insnName, insnDescriptor)) {
                            callers.add(topLevel(owner));
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDescriptor, Handle bootstrap,
                                                       Object... bootstrapArgs) {
                        for (Object arg : bootstrapArgs) {
                            if (arg instanceof Handle h && isOpen(h.getName(), h.getDesc())) {
                                callers.add(topLevel(owner));
                            }
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    private static boolean isOpen(String name, String descriptor) {
        return "open".equals(name) && descriptor != null && descriptor.startsWith(OPEN_DESCRIPTOR_PREFIX);
    }

    /** 内部类、匿名类、lambda 所在的类都归到最外层的那个类上。 */
    private static String topLevel(String internalName) {
        String binary = internalName.replace('/', '.');
        int dollar = binary.indexOf('$');
        return dollar < 0 ? binary : binary.substring(0, dollar);
    }
}
