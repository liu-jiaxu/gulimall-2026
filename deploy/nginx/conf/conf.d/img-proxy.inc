# ============================================================
# 对象存储转发片段：/oss/<bucket>/<对象> → MinIO
# ------------------------------------------------------------
# 本文件不是独立站点，而是被各 server 块 include 进来。
# 当前被 3 个站点 include：gulimall.vip、admin.gulimall.vip、search.gulimall.vip
# （img.gulimall.vip 不走这里 —— 它整个 location / 直接反代 MinIO，见 gulimall.conf）。
#
# 数据库 / ES 里存的图片地址就是这个形式（【根相对路径】），形如：
#     /oss/gulimall/brand/2026-09-07/xxx.png
#
# 【⚠️ 为什么前缀是 /oss 而不是 /img —— 这个坑踩过】
#   前端模板里本来就有大量相对引用写的是 `img/xxx.jpg`（JD 模板自带的占位图，几百处）。
#   页面在根路径下时（搜索页就是 /list.html），这些相对路径会被浏览器解析成
#   /img/xxx.jpg —— 和"图片资源前缀 /img/"撞在一起。
#   于是 nginx 把它们也转给了 MinIO，MinIO 收到的不含 bucket，直接返回：
#       400 Bad Request  Content-Type: application/xml  （还带着 X-Amz-* 响应头）
#   换成 /oss 后不再冲突（已 grep 确认全项目没有以 oss/ 开头的相对引用）。
#
# 【为什么存相对路径，而不是带 IP / 域名的绝对地址】
#   以 / 开头时，浏览器会用【当前页面的源】自动补全：
#     页面在 gulimall.vip  →  http://gulimall.vip/oss/gulimall/...
#     将来换域名 / 上公网   →  自动跟着变，数据库一个字都不用改
#   图片跟着页面走 —— 所以库里【不存任何 IP / 域名】。
#   存绝对地址则做不到：域名或 IP 一变，存量数据的地址全失效。
#   ⚠️ 代价：必须【经过 nginx】访问。直连服务端口（localhost:10000、localhost:12000）
#      时 /oss/... 会打到那个服务上 → 404 —— 开发时务必用域名访问。
#
# ⚠️ 必须用 ^~ 修饰符。
#    各站点的静态资源用正则在拦（location ~* \.(png|jpg|...)$），
#    而 nginx 的优先级是【正则 > 前缀匹配】—— 不加 ^~ 的话 /oss/xxx.png 会被那条正则截走，
#    当成本地静态文件去找，找不到再回退给后端 → 后端没这个路径 → 404。
#    ^~ 的语义是"前缀命中后【不再检查正则】"，正是这里要的。
#
# ⚠️ proxy_pass 末尾的斜杠不能少。
#    它会把 /oss/ 这段前缀替换掉再转发：
#        /oss/gulimall/brand/x.png  →  MinIO 收到  /gulimall/brand/x.png（bucket/对象）
#    写成不带斜杠的 http://minio:9000，会把 /oss 一起带过去 → MinIO 400/404。
#
# minio 是 compose 网络内的服务名，容器之间可直接访问。
# 注意连的是【容器内的 9000】，不是虚拟机的 9000 —— 宿主上对外映射的是 19000:9000，
# 容器之间走 compose 网络直连，不经过宿主端口。和站点三 img.gulimall.vip 是同一个写法。
#
# ⚠️ nginx 在【加载配置时】就要解析 minio 这个名字，解析不了会直接启动失败
#    （不是运行时 502，是 nginx 根本起不来）。这个约束【本来就有】——
#    img.gulimall.vip 站点一直就是这么写的，不是本文件引入的。
#    已在 docker-compose.yaml 的 nginx 服务里加 depends_on: minio 保证启动顺序。
#
# 【将来 MinIO 上分布式集群时怎么改】
#   现在是单节点写法：一个服务名 = 一个地址。集群模式下要改成 upstream 列多个节点，
#   照抄同一份 conf.d 里 gateway 那段 upstream 的写法：
#
#       upstream minio_cluster {
#           server minio1:9000;
#           server minio2:9000;
#           server minio3:9000;
#           server minio4:9000;
#       }
#       location ^~ /oss/ { proxy_pass http://minio_cluster/; }
#
#   分布式 MinIO 的任意节点都能服务任意对象（对象落在哪个节点由集群内部路由），
#   所以简单轮询是安全的，不需要一致性哈希。
#   ⚠️ upstream 里的域名同样是【启动时】解析的，有一个节点解析不了 nginx 就起不来；
#      nginx 1.28 支持给 upstream 成员加 `resolve` 并配 `resolver`，改成运行时解析。
# ============================================================

location ^~ /oss/ {

    proxy_pass http://minio:9000/;

    proxy_http_version 1.1;

    proxy_set_header Host            $host;
    proxy_set_header X-Real-IP       $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    # 图片是静态内容，让浏览器缓存 7 天，减少回源
    expires 7d;
    add_header Cache-Control "public";

    # 只放行读请求。
    # 上传是前端拿 presigned URL 直传 MinIO 的 19000 端口，不经过这里，
    # 所以没必要开放写方法，关掉更安全（和 img.gulimall.vip 站点保持一致）。
    limit_except GET HEAD {
        deny all;
    }
}
