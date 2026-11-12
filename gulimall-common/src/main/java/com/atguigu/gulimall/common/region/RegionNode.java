package com.atguigu.gulimall.common.region;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 省 / 市 / 区节点，对应 region/pca-code.json 的结构。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegionNode implements Serializable {

    private static final long serialVersionUID = 1L;

    private String code;

    private String name;

    private List<RegionNode> children;
}
