package com.atguigu.gulimall.agent.image;

import java.util.List;
import java.util.Locale;

/**
 * 图片生成提示词守卫：只允许和谷粒商城相关的图片需求。
 *
 * <p>模型有时会把“画一只猫”改写成很详细的艺术提示词，单靠系统提示词不够稳；
 * 这里在提交任务前再做一次确定性校验，避免用户把 AI 当成通用画图工具。
 *
 * <p>判定规则：提示词里必须出现商城/商品/营销/分类相关词（中英文都算）。
 * 这样“华为手机促销海报”“生鲜商品主图”“谷粒商城 logo”能过，
 * “画一只橘猫”“风景插画”“动漫人物”会被拒绝。
 */
public final class ImagePromptGuard {

    private static final List<String> RELATED_KEYWORDS = List.of(
            // 商城/平台
            "谷粒", "商城", "购物", "电商", "网店", "店铺", "品牌", "会员", "gulimall", "mall", "shop",
            "e-commerce", "ecommerce", "commerce",
            // 商品/页面
            "商品", "产品", "主图", "详情页", "详情图", "货架", "包装", "礼盒", "上新", "分类",
            "product", "sku", "spu", "listing",
            // 营销/活动
            "海报", "促销", "优惠", "折扣", "秒杀", "活动", "横幅", "推广", "宣传", "广告",
            "优惠券", "红包", "满减", "banner", "poster", "promotion", "coupon", "seckill",
            "discount", "sale", "marketing", "advertisement", "logo",
            // 常见商品类目
            "手机", "服饰", "家电", "生鲜", "美妆", "母婴", "鞋", "箱包", "数码", "图书",
            "厨具", "珠宝", "汽车用品", "运动", "玩具", "食品", "饮料", "保健", "钟表", "电脑",
            "办公", "家居", "家装", "内衣", "乐器", "旅行", "充值", "票务", "整车"
    );

    /**
     * 工具类禁止实例化。
     */
    private ImagePromptGuard() {
    }

    /**
     * 是否属于商城相关图片需求。
     *
     * @param prompt 用户输入的图片描述
     * @return 命中商城相关关键词时为 true，否则为 false
     */
    public static boolean isProjectRelated(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return false;
        }
        // 1. 英文关键词统一转小写，中文不受影响
        String text = prompt.toLowerCase(Locale.ROOT);

        // 2. 命中任意一个商城/商品/营销关键词即允许
        for (String keyword : RELATED_KEYWORDS) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
