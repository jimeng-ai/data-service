package com.jimeng.dataserver.ai.connector.spi;

import java.util.List;

/**
 * 一个连接器参数的定义。
 *
 * <p>这份定义<b>同时</b>驱动两件事：后端录入校验、前端表单渲染。
 * 一份定义两处用，是「新增一种连接器类型，前端零改动」这条验收标准的全部实现手段——
 * 否则每加一种类型就要写一个新表单页，而表单页恰恰是最容易写出不一致行为的地方。
 *
 * @param name        参数名，进 {@code config_json}（{@code secret=true} 的除外，那些进凭据密文）
 * @param label       给人看的名字
 * @param type        类型，决定校验与控件
 * @param required    必填
 * @param secret      敏感。<b>true 的参数不进 config_json，只进 AES-GCM 密文，且永不回读</b>
 * @param defaultValue 默认值（字符串形式，按 type 解析）；null 表示无默认
 * @param placeholder 输入框占位提示
 * @param help        帮助文案，解释这个参数是什么、填错会怎样
 * @param options     {@link ParamType#ENUM} 的候选值
 * @param pattern     正则校验（可空）
 * @param min         {@link ParamType#INT} 的下界（可空）
 * @param max         {@link ParamType#INT} 的上界（可空）
 * @param group       分组名，前端据此分段渲染；null 归到「基本」
 */
public record ParamField(
        String name,
        String label,
        ParamType type,
        boolean required,
        boolean secret,
        String defaultValue,
        String placeholder,
        String help,
        List<String> options,
        String pattern,
        Integer min,
        Integer max,
        String group
) {

    /** 最常用的构造：非敏感、无默认、无约束。 */
    public static ParamField of(String name, String label, ParamType type, boolean required, String help) {
        return new ParamField(name, label, type, required, false, null, null, help, null, null, null, null, null);
    }

    /** 敏感参数：不进 config_json，只进凭据密文。 */
    public static ParamField secret(String name, String label, boolean required, String help) {
        return new ParamField(name, label, ParamType.PASSWORD, required, true, null, null, help, null, null, null, null, null);
    }

    public ParamField withDefault(String v) {
        return new ParamField(name, label, type, required, secret, v, placeholder, help, options, pattern, min, max, group);
    }

    public ParamField withOptions(List<String> opts) {
        return new ParamField(name, label, ParamType.ENUM, required, secret, defaultValue, placeholder, help, opts, pattern, min, max, group);
    }

    public ParamField withRange(Integer lo, Integer hi) {
        return new ParamField(name, label, type, required, secret, defaultValue, placeholder, help, options, pattern, lo, hi, group);
    }

    public ParamField withPattern(String re) {
        return new ParamField(name, label, type, required, secret, defaultValue, placeholder, help, options, re, min, max, group);
    }

    public ParamField inGroup(String g) {
        return new ParamField(name, label, type, required, secret, defaultValue, placeholder, help, options, pattern, min, max, g);
    }
}
