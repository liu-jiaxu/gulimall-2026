package com.atguigu.gulimall.search.service;

import com.atguigu.gulimall.search.vo.SearchParam;
import com.atguigu.gulimall.search.vo.SearchResult;

/**
 * @ClassName: MallSearchService
 * @Package: com.atguigu.gulimall.search.service
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/9/24 - 18:44
 * @Version: v1.0
 */

public interface MallSearchService {

    SearchResult search(SearchParam searchParam);

}
