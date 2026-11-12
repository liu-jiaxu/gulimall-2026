出于信息安全考虑，必须将所有私密数据单独存储

本地测试
1.docker-compose.env
  改成 WINDOWS_HOST_IP=WINDOWS_HOST_IP=<your-windows-host-ip>
2.添加hosts配置
<your-vm-host-ip>  gulimall.vip
<your-vm-host-ip>  admin.gulimall.vip
<your-vm-host-ip>  img.gulimall.vip
<your-vm-host-ip>  search.gulimall.vip
<your-vm-host-ip>  auth.gulimall.vip
<your-vm-host-ip>  cart.gulimall.vip
<your-vm-host-ip>  order.gulimall.vip
<your-vm-host-ip>  pay.gulimall.vip
<your-vm-host-ip>  seckill.gulimall.vip
<your-vm-host-ip>  member.gulimall.vip
3.如果本地之前已经部署访问过域名，本地再次测试时会走https，需要访问：
edge://net-internals/#hsts
依次删除
gulimall.vip
admin.gulimall.vip
img.gulimall.vip
search.gulimall.vip
auth.gulimall.vip
cart.gulimall.vip
order.gulimall.vip
pay.gulimall.vip
seckill.gulimall.vip
member.gulimall.vip

部署时：
1.docker-compose.env
  改成 WINDOWS_HOST_IP=host-gateway
2.hosts配置全部注释