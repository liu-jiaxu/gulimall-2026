-- 图片地址：/img/gulimall/...  →  /oss/gulimall/...
-- 执行库：gulimall_pms
UPDATE pms_brand          SET logo            = REPLACE(logo,            '/img/gulimall', '/oss/gulimall') WHERE logo            LIKE '%/img/gulimall%';
UPDATE pms_sku_info       SET sku_default_img = REPLACE(sku_default_img, '/img/gulimall', '/oss/gulimall') WHERE sku_default_img LIKE '%/img/gulimall%';
UPDATE pms_sku_images     SET img_url         = REPLACE(img_url,         '/img/gulimall', '/oss/gulimall') WHERE img_url         LIKE '%/img/gulimall%';
UPDATE pms_spu_images     SET img_url         = REPLACE(img_url,         '/img/gulimall', '/oss/gulimall') WHERE img_url         LIKE '%/img/gulimall%';
UPDATE pms_spu_info_desc  SET decript         = REPLACE(decript,         '/img/gulimall', '/oss/gulimall') WHERE decript         LIKE '%/img/gulimall%';
UPDATE pms_attr_group     SET icon            = REPLACE(icon,            '/img/gulimall', '/oss/gulimall') WHERE icon            LIKE '%/img/gulimall%';


-- 校验：下面每行都应返回 0
SELECT 'brand.logo'               AS col, COUNT(*) AS remains FROM pms_brand          WHERE logo            LIKE '%/img/gulimall%'
UNION ALL SELECT 'sku_info.sku_default_img',  COUNT(*) FROM pms_sku_info       WHERE sku_default_img LIKE '%/img/gulimall%'
UNION ALL SELECT 'sku_images.img_url',        COUNT(*) FROM pms_sku_images     WHERE img_url         LIKE '%/img/gulimall%'
UNION ALL SELECT 'spu_images.img_url',        COUNT(*) FROM pms_spu_images     WHERE img_url         LIKE '%/img/gulimall%'
UNION ALL SELECT 'spu_info_desc.decript',     COUNT(*) FROM pms_spu_info_desc  WHERE decript         LIKE '%/img/gulimall%'
UNION ALL SELECT 'attr_group.icon',           COUNT(*) FROM pms_attr_group     WHERE icon            LIKE '%/img/gulimall%';
