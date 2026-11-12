# ============================================================
# 谷粒商城 AI 助手：所有用户端/管理端站点共用
# ------------------------------------------------------------
# 1. /gulimall/agent/** 转发到网关，支持 SSE 长连接（必须关缓冲）
# 2. /gulimall/*/internal/** 是业务模块给 agent 的内部接口，对外一律 404
# ============================================================

location ^~ /gulimall/agent/ {
    proxy_pass http://gulimall_gateway;

    # SSE：HTTP/1.1 + 清空 Connection，才能保持长连接
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    # 关键：不缓冲，否则前端会等模型全部生成完才一次性收到
    proxy_buffering off;
    proxy_cache off;
    gzip off;

    # 模型思考 + 工具调用可能超过 1 分钟
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;
}

# 内部只读接口不允许外部访问（即使知道路径也拿不到 X-Internal-Token）
location ~ ^/gulimall/[^/]+/internal/ {
    return 404;
}
