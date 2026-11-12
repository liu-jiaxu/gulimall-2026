package com.atguigu.gulimall.product.method;

import com.atguigu.gulimall.product.dao.CategoryDao;
import com.atguigu.gulimall.product.entity.CategoryEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @ClassName: CategoryMethod
 * @Package: com.atguigu.gulimall.product.method
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/9 - 11:12
 * @Version: v1.0
 */
@Component
public class CategoryMethod {

    @Autowired
    private CategoryDao categoryDao;

    /**
     * 找到catelogId的完整路径 [父/子/孙]
     * @param catelogId
     * @return
     */
    public Long[] findCatelogPath(Long catelogId) {
        List<Long> path = new java.util.ArrayList<>();
        findParentPath(catelogId, path);
        return path.toArray(new Long[0]);
    }

    private void findParentPath(Long catelogId, List<Long> path) {
        // 1.查询当前节点
        CategoryEntity category = categoryDao.selectById(catelogId);
        if (category != null) {
            // 2.将当前节点的ID添加到路径中
            path.addFirst(catelogId);
            // 3.递归查找父节点
            findParentPath(category.getParentCid(), path);
        }
    }

}
