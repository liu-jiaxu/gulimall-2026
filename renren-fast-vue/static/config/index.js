/**
 * 开发环境
 */
; (function () {
  window.SITE_CONFIG = {};

  // api接口请求地址
  // 开发环境统一用【同源相对路径】，由 dev server 的 /gulimall 代理转发到本地网关 88。
  // 好处：localhost:8001 和 admin.gulimall.vip（Tunnel 指向 8001）都能用，
  // 不会出现浏览器把 localhost:88 解析成访问者自己电脑的问题。
  window.SITE_CONFIG['baseUrl'] = '/gulimall';
  // 【旧】直连网关（仅限本机浏览器访问 localhost:8001 时可用）
  // window.SITE_CONFIG['baseUrl'] = 'http://localhost:88/gulimall';

  // cdn地址 = 域名 + 版本号
  window.SITE_CONFIG['domain'] = './'; // 域名
  window.SITE_CONFIG['version'] = '';   // 版本号(年月日时分)
  window.SITE_CONFIG['cdnUrl'] = window.SITE_CONFIG.domain + window.SITE_CONFIG.version;
})();
