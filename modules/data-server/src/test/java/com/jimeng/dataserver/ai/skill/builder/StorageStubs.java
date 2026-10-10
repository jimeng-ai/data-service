package com.jimeng.dataserver.ai.skill.builder;

import com.jimeng.dataserver.ai.rag.service.storage.RagMinioStorageService;

import java.util.Arrays;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 让 mock 的存储像真的一样：{@code readBytes} 整个读，{@code readBytesUpTo(name, max)} 最多给 max + 1 个字节。 */
final class StorageStubs {

    private StorageStubs() {}

    static void object(RagMinioStorageService storage, String name, byte[] bytes) throws Exception {
        when(storage.readBytes(name)).thenReturn(bytes);
        when(storage.readBytesUpTo(eq(name), anyLong())).thenAnswer(inv -> {
            long max = inv.getArgument(1);
            return Arrays.copyOf(bytes, (int) Math.min(bytes.length, max + 1));
        });
    }
}
