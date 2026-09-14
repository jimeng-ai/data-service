package com.jimeng.dataserver.ai.connector.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PII 过滤。这个类的失效方式是<b>单向致命</b>的，所以测试也按那个方向组织：
 *
 * <ul>
 *   <li><b>漏报</b>（该拦没拦）= 客户的手机号进我们的库、进模型提示词、进
 *       {@code ai_model_call_content}，三份副本，发现时收不回来。下面「必须拦下」那一组
 *       每少一条，就是这类事故多一个入口。</li>
 *   <li><b>误报</b>（不该拦拦了）= 少一个值域，可恢复。只有一处误报是不可接受的：
 *       把 {@code 华东} / {@code 浙江省} 这类<b>维值</b>当成人名或地址拦掉，
 *       那等于把整个值域剖析功能关了——见「不能误杀的维值」那一组。</li>
 * </ul>
 *
 * <p>另外单独钉住两件容易在重构中被「优化」掉的事：
 * 列名判定<b>不依赖取值</b>（空表上的手机号列照样拦），
 * 以及判定理由里<b>绝不回显原始取值</b>。
 */
class PiiFilterTest {

    private final PiiFilter filter = new PiiFilter();

    @Nested
    @DisplayName("列名这一层：必须拦下")
    class ColumnNameMustBlock {

        @Test
        @DisplayName("同一个概念的三种写法必须表现一致，否则漏报是随机的")
        void sameConceptDifferentSpellings() {
            for (String col : List.of("phone", "user_phone", "userPhone", "USER_PHONE", "mobile", "Mobile")) {
                assertTrue(filter.screenColumn("t_user", col, null).sensitive(), col);
            }
        }

        @Test
        @DisplayName("★ 一列叫 phone，此刻每一行都是空的，它依然是手机号列")
        void emptyPhoneColumnIsStillAPhoneColumn() {
            // 只看取值形状的过滤会在这里放行，下一次刷新时真实号码就静默进来了。
            assertTrue(filter.screen("t_user", "phone", null, List.of()).sensitive());
            assertTrue(filter.screen("t_user", "phone", null, null).sensitive());
        }

        @Test
        @DisplayName("词对：id_card / bank_card / account_no 这类拆开看都不敏感的组合")
        void sensitivePairs() {
            for (String col : List.of("id_card", "idCardNo", "bank_card", "bank_account",
                    "account_no", "credit_card", "device_id", "license_plate")) {
                assertTrue(filter.screenColumn("t", col, null).sensitive(), col);
            }
        }

        @Test
        @DisplayName("中文列名与中文列注释都要判——客户的库里两种都有")
        void chineseNameAndComment() {
            assertTrue(filter.screenColumn("t", "sjh", "手机号").sensitive());
            assertTrue(filter.screenColumn("t", "f1", "客户身份证号码").sensitive());
            assertTrue(filter.screenColumn("t", "col3", "收货地址").sensitive());
        }

        @Test
        @DisplayName("秘密类列名：5 个共享的 API key 泄漏一次就够了")
        void secretsAreBlockedToo() {
            for (String col : List.of("password", "pwd", "api_key", "token", "secret", "openid")) {
                assertTrue(filter.screenColumn("t", col, null).sensitive(), col);
            }
        }

        @Test
        @DisplayName("列名为空时判不了，判不了就当敏感")
        void blankColumnNameFailsClosed() {
            assertTrue(filter.screenColumn("t", null, null).sensitive());
            assertTrue(filter.screenColumn("t", "  ", null).sensitive());
        }
    }

    @Nested
    @DisplayName("列名这一层：不能误杀的维值列")
    class ColumnNameMustPass {

        @Test
        @DisplayName("★ region_name / status_name 是维值列，拦掉它们等于把这个功能关了")
        void qualifiedNonPersonNamesPass() {
            for (String col : List.of("region_name", "status_name", "type_name", "product_name",
                    "dept_name", "category_name", "channel_name")) {
                assertFalse(filter.screenColumn("t_order", col, null).sensitive(), col);
            }
        }

        @Test
        @DisplayName("★ 词元边界：ship_type 含 ip、package_type 含 age，都不是 PII")
        void tokenBoundaryNotSubstring() {
            // 子串匹配会让这几列被随机误杀，而误杀与否取决于业务词里碰巧藏了哪几个字母。
            for (String col : List.of("ship_type", "package_type", "message_type", "stage",
                    "usage_count", "hotel_id", "equipment_no", "description")) {
                assertFalse(filter.screenColumn("t", col, null).sensitive(), col);
            }
        }

        @Test
        @DisplayName("name 的限定词是人才拦")
        void nameQualifierDecides() {
            assertTrue(filter.screenColumn("t", "user_name", null).sensitive());
            assertTrue(filter.screenColumn("t", "customer_name", null).sensitive());
            assertTrue(filter.screenColumn("t", "contactName", null).sensitive());
            assertFalse(filter.screenColumn("t", "region_name", null).sensitive());
        }

        @Test
        @DisplayName("★ 列就叫 name 时限定词缺失，按最严处理——代价写在 PERSON_QUALIFIERS 的注释里")
        void bareNameFailsClosed() {
            assertTrue(filter.screenColumn("t_region", "name", null).sensitive());
        }
    }

    @Nested
    @DisplayName("取值这一层：必须拦下")
    class ValuesMustBlock {

        @Test
        void email() {
            assertTrue(filter.screenValues(List.of("正常", "a@b.com")).sensitive());
        }

        @Test
        void chineseIdCard() {
            assertTrue(filter.screenValues(List.of("11010119900307001X")).sensitive());
            assertTrue(filter.screenValues(List.of("110101900307001")).sensitive());
        }

        @Test
        @DisplayName("长数字串一条规则覆盖手机号 / 银行卡 / QQ / 固话")
        void longDigitStrings() {
            assertTrue(filter.screenValues(List.of("13800138000")).sensitive());
            assertTrue(filter.screenValues(List.of("6222 0202 0000 1234")).sensitive());
            assertTrue(filter.screenValues(List.of("010-12345678")).sensitive());
        }

        @Test
        void ipAddress() {
            assertTrue(filter.screenValues(List.of("192.168.1.1")).sensitive());
        }

        @Test
        @DisplayName("门牌级地址拦下")
        void streetAddress() {
            assertTrue(filter.screenValues(List.of("北京市海淀区中关村大街1号")).sensitive());
        }

        @Test
        @DisplayName("★ 命中一个取值就否决整批，不是只丢那一个")
        void oneHitDropsTheWholeSet() {
            // 留下一个「缺了几项的集合」会被当成全集用，比没有值域更危险。
            assertTrue(filter.screenValues(List.of("华东", "华北", "13800138000")).sensitive());
        }
    }

    @Nested
    @DisplayName("取值这一层：不能误杀")
    class ValuesMustPass {

        @Test
        @DisplayName("★ 华东 / 华北 —— 整个值域剖析要解决的就是这个案例")
        void theMotivatingCaseSurvives() {
            assertFalse(filter.screenValues(List.of("华东", "华北", "华南", "西南")).sensitive());
        }

        @Test
        @DisplayName("★ 行政区划不是详细地址：省 / 市 / 区 / 县是最典型的维值")
        void administrativeDivisionsAreNotAddresses() {
            assertFalse(filter.screenValues(List.of("浙江省", "北京市", "海淀区", "黑龙江省")).sensitive());
        }

        @Test
        @DisplayName("状态码与短编码：0/1/2/3 是这个功能最主要的目标")
        void statusCodes() {
            assertFalse(filter.screenValues(List.of("0", "1", "2", "3")).sensitive());
            assertFalse(filter.screenValues(List.of("PAID", "UNPAID", "REFUNDED")).sensitive());
            assertFalse(filter.screenValues(List.of("2024", "2025")).sensitive());
        }

        @Test
        void emptyInputIsClean() {
            assertFalse(filter.screenValues(null).sensitive());
            assertFalse(filter.screenValues(List.of()).sensitive());
        }
    }

    @Nested
    @DisplayName("判定理由与日志")
    class ReasonHygiene {

        @Test
        @DisplayName("★ 判定理由里绝不出现原始取值——那句话会进我们的库和模型上下文")
        void reasonNeverEchoesTheValue() {
            String phone = "13800138000";
            String reason = filter.screenValues(List.of(phone)).reason();
            assertNotNull(reason);
            assertFalse(reason.contains(phone), "理由回显了被判定为 PII 的取值：" + reason);
        }

        @Test
        void maskForLogKeepsNothingUsable() {
            String masked = PiiFilter.maskForLog("13800138000");
            assertFalse(masked.contains("13800138000"));
            assertTrue(masked.contains("len=11"));
            assertTrue(masked.startsWith("1"));
            // 边界：null 与空串不能把日志语句本身弄崩
            assertTrue(PiiFilter.maskForLog(null).contains("null"));
            assertTrue(PiiFilter.maskForLog("").contains("空串"));
        }
    }

    @Nested
    @DisplayName("词元化")
    class Tokenize {

        @Test
        @DisplayName("驼峰、下划线、大写三种写法切出同一组词元")
        void spellingsNormalizeToTheSameTokens() {
            assertTrue(PiiFilter.tokenize("userPhone").equals(List.of("user", "phone")));
            assertTrue(PiiFilter.tokenize("user_phone").equals(List.of("user", "phone")));
            assertTrue(PiiFilter.tokenize("USER_PHONE").equals(List.of("user", "phone")));
        }
    }
}
