package com.jimeng.dataserver.ai.connector.generation.outcome;

import com.jimeng.dataserver.ai.connector.generation.GenerationReasonCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Complete outcome strategy registry. Handlers return commands only; O5 performs transactional state changes.
 */
@Component
public final class SliceOutcomeHandlerRegistry {

    private static final String MODEL_MISMATCH_NOTE =
            "模型配置有误，请联系平台管理员";
    private static final String NO_CALLBACK_NOTE =
            "生成服务没有回应，请联系平台管理员";

    private final Map<SliceOutcomeKind, SliceOutcomeHandler> handlers;

    @Autowired
    public SliceOutcomeHandlerRegistry() {
        this(defaultHandlers());
    }

    public SliceOutcomeHandlerRegistry(List<SliceOutcomeHandler> candidates) {
        EnumMap<SliceOutcomeKind, SliceOutcomeHandler> built = new EnumMap<>(SliceOutcomeKind.class);
        for (SliceOutcomeHandler handler : candidates) {
            SliceOutcomeHandler previous = built.putIfAbsent(handler.kind(), handler);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate outcome handler: " + handler.kind());
            }
        }
        if (built.size() != SliceOutcomeKind.values().length) {
            List<SliceOutcomeKind> missing = new ArrayList<>();
            for (SliceOutcomeKind kind : SliceOutcomeKind.values()) {
                if (!built.containsKey(kind)) {
                    missing.add(kind);
                }
            }
            throw new IllegalArgumentException("missing outcome handlers: " + missing);
        }
        handlers = Map.copyOf(built);
    }

    public SliceOutcomeDecision decide(SliceOutcomeContext context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        return handler(context.kind()).handle(context);
    }

    public SliceOutcomeHandler handler(SliceOutcomeKind kind) {
        SliceOutcomeHandler handler = handlers.get(kind);
        if (handler == null) {
            throw new IllegalArgumentException("unsupported outcome: " + kind);
        }
        return handler;
    }

    public Set<SliceOutcomeKind> kinds() {
        return handlers.keySet();
    }

    private static List<SliceOutcomeHandler> defaultHandlers() {
        return List.of(
                handler(SliceOutcomeKind.ABORTED, SliceOutcomeHandlerRegistry::aborted),
                handler(SliceOutcomeKind.SHUTDOWN, SliceOutcomeHandlerRegistry::shutdown),
                handler(SliceOutcomeKind.MODEL_MISMATCH, SliceOutcomeHandlerRegistry::modelMismatch),
                handler(SliceOutcomeKind.SANDBOX_REJECTED,
                        c -> degradeOrInterrupt(c, GenerationReasonCode.SANDBOX_REJECTED,
                                "生成服务配置有误，请联系平台管理员")),
                handler(SliceOutcomeKind.SANDBOX_AUTH,
                        c -> degradeOrInterrupt(c, GenerationReasonCode.SANDBOX_AUTH,
                                "生成服务配置有误，请联系平台管理员")),
                handler(SliceOutcomeKind.SANDBOX_UNAVAILABLE,
                        c -> degradeOrInterrupt(c, GenerationReasonCode.SANDBOX_UNAVAILABLE,
                                "生成服务配置有误，请联系平台管理员")),
                handler(SliceOutcomeKind.BUSY_EXHAUSTED,
                        c -> degradeOrInterrupt(c, GenerationReasonCode.SANDBOX_BUSY,
                                "服务繁忙或不可用")),
                handler(SliceOutcomeKind.CLI_BUDGET, SliceOutcomeHandlerRegistry::cliBudget),
                handler(SliceOutcomeKind.PROGRESS, SliceOutcomeHandlerRegistry::progress),
                handler(SliceOutcomeKind.NO_CALLBACK, SliceOutcomeHandlerRegistry::noCallback),
                handler(SliceOutcomeKind.NO_PROGRESS, SliceOutcomeHandlerRegistry::noProgress));
    }

    private static SliceOutcomeHandler handler(SliceOutcomeKind kind,
                                               Function<SliceOutcomeContext, SliceOutcomeDecision> function) {
        return new SliceOutcomeHandler() {
            @Override
            public SliceOutcomeKind kind() {
                return kind;
            }

            @Override
            public SliceOutcomeDecision handle(SliceOutcomeContext context) {
                return function.apply(context);
            }
        };
    }

    private static SliceOutcomeDecision aborted(SliceOutcomeContext context) {
        return decision(SliceOutcomeAction.ABORT, null, null, context, 0);
    }

    private static SliceOutcomeDecision shutdown(SliceOutcomeContext context) {
        return decision(SliceOutcomeAction.INTERRUPT, GenerationReasonCode.SHUTDOWN,
                "服务重启", context, 0);
    }

    private static SliceOutcomeDecision modelMismatch(SliceOutcomeContext context) {
        return decision(SliceOutcomeAction.FAIL, GenerationReasonCode.MODEL_MISMATCH,
                MODEL_MISMATCH_NOTE, context, 0);
    }

    private static SliceOutcomeDecision cliBudget(SliceOutcomeContext context) {
        return decision(SliceOutcomeAction.INTERRUPT, GenerationReasonCode.CLI_BUDGET,
                "单次用量已达上限，请联系平台管理员", context, 0);
    }

    private static SliceOutcomeDecision progress(SliceOutcomeContext context) {
        return new SliceOutcomeDecision(SliceOutcomeAction.CONTINUE, null, null, 0, 0, 0);
    }

    private static SliceOutcomeDecision noCallback(SliceOutcomeContext context) {
        int callbackStreak = context.noCallbackStreak() + 1;
        if (callbackStreak >= 2) {
            return degradeOrInterrupt(context, GenerationReasonCode.NO_CALLBACK, NO_CALLBACK_NOTE,
                    context.sliceFailStreak(), callbackStreak);
        }
        return noProgress(context, callbackStreak);
    }

    private static SliceOutcomeDecision noProgress(SliceOutcomeContext context) {
        // 只有完全没有回调才累计 NO_CALLBACK；已收到回调但没有表进展会打断连续计数。
        return noProgress(context, 0);
    }

    private static SliceOutcomeDecision noProgress(SliceOutcomeContext context, int callbackStreak) {
        int failStreak = context.sliceFailStreak() + 1;
        if (failStreak >= context.maxRetries()) {
            return degradeOrInterrupt(context, GenerationReasonCode.NO_PROGRESS,
                    "多次尝试都没有进展", failStreak, callbackStreak);
        }
        return new SliceOutcomeDecision(SliceOutcomeAction.RETRY, null, null, failStreak,
                callbackStreak, backoff(context.retryBackoffSeconds(), failStreak));
    }

    private static SliceOutcomeDecision degradeOrInterrupt(SliceOutcomeContext context,
                                                            GenerationReasonCode reasonCode,
                                                            String reason) {
        return degradeOrInterrupt(context, reasonCode, reason,
                context.sliceFailStreak(), context.noCallbackStreak());
    }

    private static SliceOutcomeDecision degradeOrInterrupt(SliceOutcomeContext context,
                                                            GenerationReasonCode reasonCode,
                                                            String reason,
                                                            int failStreak,
                                                            int callbackStreak) {
        if (context.doneTables() > 0) {
            return new SliceOutcomeDecision(SliceOutcomeAction.INTERRUPT, reasonCode, reason,
                    failStreak, callbackStreak, 0);
        }
        return new SliceOutcomeDecision(SliceOutcomeAction.FALLBACK, reasonCode,
                "降级原因：" + stripTrailingPunctuation(reason) + "。",
                failStreak, callbackStreak, 0);
    }

    private static SliceOutcomeDecision decision(SliceOutcomeAction action,
                                                  GenerationReasonCode reasonCode,
                                                  String note,
                                                  SliceOutcomeContext context,
                                                  int backoff) {
        return new SliceOutcomeDecision(action, reasonCode, note, context.sliceFailStreak(),
                context.noCallbackStreak(), backoff);
    }

    private static int backoff(int base, int streak) {
        long multiplier = 1L << Math.min(30, Math.max(0, streak - 1));
        return (int) Math.min(300L, (long) base * multiplier);
    }

    private static String stripTrailingPunctuation(String reason) {
        String value = reason == null ? "未知原因" : reason.trim();
        while (value.endsWith("。") || value.endsWith(".") || value.endsWith("！") || value.endsWith("!")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        return value;
    }
}
