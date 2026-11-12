# 所有站点共用的地址定位代理：
# 浏览器请求同源 /location/reverse?lat=..&lng=..，
# Nginx 转发到 third-party 的 /baidu/map/reverse，避免跨域和 AK 暴露。
location /location/ {
    proxy_pass http://host.docker.internal:30000/baidu/map/;
    proxy_http_version 1.1;
    proxy_set_header Host            $host;
    proxy_set_header X-Real-IP       $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
