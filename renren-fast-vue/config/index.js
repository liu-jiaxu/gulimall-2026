'use strict'
// Template version: 1.2.5
// see http://vuejs-templates.github.io/webpack for documentation.

const path = require('path')
const devEnv = require('./dev.env')

module.exports = {
  dev: {

    // Paths
    assetsSubDirectory: 'static',
    assetsPublicPath: '/',
    // 代理列表, 是否开启代理通过[./dev.env.js]配置
    proxyTable: Object.assign(
      // ---------- /oss：图片（【始终生效】，不受 OPEN_PROXY 开关控制）----------
      // 数据库 / ES 里存的是【根相对路径】/oss/gulimall/xxx.png，
      // 浏览器会拿【当前页面的源】去补全 —— 开发时页面在 dev server(8001) 上，
      // 于是图片请求变成 http://localhost:8001/oss/... → dev server 没这个路由 → 404。
      // 这里把它代理到虚拟机的 nginx(80)，由 nginx 的 location ^~ /oss/ 再转发给 MinIO。
      // 生产环境页面本身就在 nginx 上（同源），不需要这条代理。
      {
        '/oss': {
          // 图片最终由虚拟机上的 Nginx 转发到 MinIO。
          // 直接指向虚拟机 IP，dev server 用 Node 请求，不经过浏览器 hosts。
          target: 'http://<your-vm-host-ip>',
          changeOrigin: true
        },
        // ---------- /gulimall：接口统一走同源，再代理到本地网关 ----------
        // 页面既可以用 http://localhost:8001 打开，也可以让 Cloudflare Tunnel
        // 把 admin.gulimall.vip 指向 8001；两种方式接口都用 /gulimall 同源请求，
        // 不会出现“浏览器把 localhost:88 当成访问者自己电脑”的问题。
        '/gulimall': {
          target: 'http://localhost:88',
          changeOrigin: true
        }
      },
      // ---------- /proxyApi：renren 官方演示接口（受 OPEN_PROXY 开关控制）----------
      devEnv.OPEN_PROXY === false ? {} : {
        '/proxyApi': {
          target: 'http://demo.renren.io/renren-fast/',
          changeOrigin: true,
          pathRewrite: {
            '^/proxyApi': '/'
          }
        }
      }
    ),

    // Various Dev Server settings
    host: 'localhost', // can be overwritten by process.env.HOST
    port: 8001, // can be overwritten by process.env.PORT, if port is in use, a free one will be determined
    autoOpenBrowser: true,
    errorOverlay: true,
    notifyOnErrors: true,
    poll: false, // https://webpack.js.org/configuration/dev-server/#devserver-watchoptions-

    // Use Eslint Loader?
    // If true, your code will be linted during bundling and
    // linting errors and warnings will be shown in the console.
    useEslint: false,
    // If true, eslint errors and warnings will also be shown in the error overlay
    // in the browser.
    showEslintErrorsInOverlay: false,

    /**
     * Source Maps
     */

    // https://webpack.js.org/configuration/devtool/#development
    devtool: 'eval-source-map',

    // If you have problems debugging vue-files in devtools,
    // set this to false - it *may* help
    // https://vue-loader.vuejs.org/en/options.html#cachebusting
    cacheBusting: true,

    // CSS Sourcemaps off by default because relative paths are "buggy"
    // with this option, according to the CSS-Loader README
    // (https://github.com/webpack/css-loader#sourcemaps)
    // In our experience, they generally work as expected,
    // just be aware of this issue when enabling this option.
    cssSourceMap: false,
  },

  build: {
    // Template for index.html
    index: path.resolve(__dirname, '../dist/index.html'),

    // Paths
    assetsRoot: path.resolve(__dirname, '../dist'),
    assetsSubDirectory: 'static',
    assetsPublicPath: './',

    /**
     * Source Maps
     */

    productionSourceMap: false,
    // https://webpack.js.org/configuration/devtool/#production
    devtool: '#source-map',

    // Gzip off by default as many popular static hosts such as
    // Surge or Netlify already gzip all static assets for you.
    // Before setting to `true`, make sure to:
    // npm install --save-dev compression-webpack-plugin
    productionGzip: false,
    productionGzipExtensions: ['js', 'css'],

    // Run the build command with an extra argument to
    // View the bundle analyzer report after build finishes:
    // `npm run build --report`
    // Set to `true` or `false` to always turn it on or off
    bundleAnalyzerReport: process.env.npm_config_report
  }
}
