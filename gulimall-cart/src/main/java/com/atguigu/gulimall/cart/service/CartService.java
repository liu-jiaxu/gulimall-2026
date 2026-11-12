package com.atguigu.gulimall.cart.service;

import com.atguigu.gulimall.api.cart.CartItemVo;
import com.atguigu.gulimall.api.cart.CartVo;

import java.util.List;

/**
 * @ClassName: CartService
 * @Package: com.atguigu.gulimall.cart.service
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/7 - 17:02
 * @Version: v1.0
 */

public interface CartService {

    void addToCart(Long skuId, Integer num);

    CartItemVo getCartItem(Long skuId);

    CartVo getCart();

    void checkItem(Long skuId, Integer checked);

    /**
     * 全选 / 取消全选：把当前购物车里每个商品的选中状态都写回 Redis
     *
     * @param checked 1 全选，0 取消全选
     */
    void checkAll(Integer checked);

    void changeItemCount(Long skuId, Integer num);

    void deleteIdCartInfo(Integer skuId);

    /**
     * 批量删除购物项（删除选中的商品）
     *
     * @param skuIds 要删除的 skuId 集合
     */
    void deleteCartItems(List<Integer> skuIds);

    /**
     * 清除下柜商品：查库存，库存为 0 的购物项直接从购物车里删掉
     */
    void clearNoStockItems();

    /**
     * 购物车件数
     */
    Long getCartCountType();

    List<CartItemVo> getUserCartItems();
}
