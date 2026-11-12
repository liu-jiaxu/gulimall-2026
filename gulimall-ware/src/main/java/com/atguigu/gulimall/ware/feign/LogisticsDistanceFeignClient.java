package com.atguigu.gulimall.ware.feign;

import com.atguigu.gulimall.api.thirdparty.LogisticsDistanceRequest;
import com.atguigu.gulimall.common.utils.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import reactor.core.publisher.Mono;

/**
 * @ClassName: LogisticsDistanceFeignClient
 * @Package: com.atguigu.gulimall.ware.feign
 * @Description:
 * @Author: 刘家旭
 * @Create: 2026/10/16 - 17:49
 * @Version: v1.0
 */
@FeignClient("gulimall-third-party")
public interface LogisticsDistanceFeignClient {

    @PostMapping("/baidu/map/distance")
    R distance(@RequestBody LogisticsDistanceRequest request);

}
