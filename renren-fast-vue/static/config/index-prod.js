/**
 * 生产环境
 */
; (function () {
  window.SITE_CONFIG = {};

  // api接口请求地址
  // 【新】用【相对路径】—— 管理端页面和接口都在同一个 Nginx 入口下
  //       （页面 admin.gulimall.com/，接口 admin.gulimall.com/gulimall/** 反代到网关），
  //       同源，所以 IP / 域名怎么变都不用改前端。
  //       对比：写死 http://admin.gulimall.com/gulimall 的话，换域名就得重新构建前端。
  window.SITE_CONFIG['baseUrl'] = '/gulimall';
  // 【旧】renren 官方演示服务器
  // window.SITE_CONFIG['baseUrl'] = 'http://demo.open.renren.io/renren-fast-server';
  // 【旧】本地开发直连网关
  // window.SITE_CONFIG['baseUrl'] = 'http://localhost:88/gulimall';
  // 【旧】走 Nginx 统一入口（写死域名）
  // window.SITE_CONFIG['baseUrl'] = 'http://admin.gulimall.com/gulimall';

  // cdn地址 = 域名 + 版本号
  window.SITE_CONFIG['domain'] = './'; // 域名
  window.SITE_CONFIG['version'] = '';   // 版本号(年月日时分)
  window.SITE_CONFIG['cdnUrl'] = window.SITE_CONFIG.domain + window.SITE_CONFIG.version;
})();
