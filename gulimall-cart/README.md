# 购物车模块优化方案（AJAX + 幂等 + Redis 持久化）

> **状态：方案，未实施。** 代码都带注释，实施时直接照抄即可。
>
> 涉及的三个问题：
> 1. 列表页每次修改（勾选 / 数量 / 删除）都是**整页跳转**（GET + redirect），体验差；
> 2. 接口是"数量 +1"这种**非幂等**写法，重复提交会多加；
> 3. Redis 用的是 `allkeys-lru`，**购物车和登录会话随时可能被静默淘汰**。

---

## 一、现状与目标

| | 现状（教学写法） | 目标（生产写法） |
|---|---|---|
| 交互 | `location.href` 整页跳转，改一件商品刷一整页 | AJAX + 局部刷新，页面不动，只改会变的那几个数字 |
| 请求方法 | GET（写操作） | POST |
| 语义 | 「数量 +1」（重复提交出错） | 「数量 = N」（幂等） |
| 防重复提交 | 靠 302 重定向把地址栏改掉 | 靠幂等 + 前端防抖 |
| Redis | `allkeys-lru`，内存满了连购物车一起删 | `noeviction`（已改，见第七节） |

**改动范围很小**：`CartController`（5 个端点）+ `CartServiceImpl`（1 处兜底）+ `cartList.html` 的一段脚本。
**不需要碰 CORS** —— 购物车页内部是同域（都在 `cart.gulimall.vip`），只有"商品详情页 → 加购"才跨域。

---

## 二、后端改造

### 2.1 `CartController`：5 个端点改成 POST + 返回整辆购物车

需要新加的 import：`org.springframework.web.bind.annotation.PostMapping`、`org.springframework.web.bind.annotation.ResponseBody`。

```java
    /* ==================== 列表页的 AJAX 接口 ====================
       三条规则：
       1. 全部 POST —— 写操作用 GET 会被浏览器/代理缓存，还可能被预取
       2. 入参都是【最终状态】而不是【增量】，所以天然幂等：
          连点、防抖合并、重复提交都不会多加一件
       3. 统一返回 {code, msg, cart}，前端只改页面上会变的那几个数字 */

    /** 勾选 / 取消勾选一件商品 */
    @ResponseBody
    @PostMapping("/checkItem")
    public R checkItem(@RequestParam("skuId") Long skuId,
                       @RequestParam("checked") Integer checked) {
        cartService.checkItem(skuId, checked);
        return cartResult();
    }

    /** 全选 / 取消全选 */
    @ResponseBody
    @PostMapping("/checkAll")
    public R checkAll(@RequestParam("checked") Integer checked) {
        cartService.checkAll(checked);
        return cartResult();
    }

    /** 修改数量：传【最终数量】，不是"加几件" */
    @ResponseBody
    @PostMapping("/countItem")
    public R countItem(@RequestParam("skuId") Long skuId,
                       @RequestParam("num") Integer num) {
        cartService.changeItemCount(skuId, num);
        return cartResult();
    }

    /** 删除一件商品 */
    @ResponseBody
    @PostMapping("/deleteItem")
    public R deleteItem(@RequestParam("skuId") Integer skuId) {
        cartService.deleteIdCartInfo(skuId);
        return cartResult();
    }

    /** 批量删除（删除选中的商品） */
    @ResponseBody
    @PostMapping("/deleteCheckedItems")
    public R deleteCheckedItems(@RequestParam("skuIds") List<Integer> skuIds) {
        cartService.deleteCartItems(skuIds);
        return cartResult();
    }

    /**
     * 改完统一返回【整辆购物车】。
     * 前端要更新的东西（每行小计、总价、共优惠、已选数量、已选种类）CartVo 里全都有 ——
     * totalPrice / totalAmount / reduce / checkedCountNum / checkedCountType 都是计算属性，
     * 直接丢回去就行，不用再定义一堆 VO
     */
    private R cartResult() {
        return R.ok().put("cart", cartService.getCart());
    }
```

### 2.2 `CartServiceImpl.changeItemCount`：加幂等兜底

```java
    @Override
    public void changeItemCount(Long skuId, Integer num) {

        // 幂等兜底：数量非法就当没收到，别把购物车改成 0 件 / 负数
        if (num == null || num < 1) {
            return;
        }

        CartItemVo cartItem = getCartItem(skuId);
        // 页面可能是过期快照（这件商品已被别处删掉），别 NPE
        if (cartItem == null) {
            return;
        }

        cartItem.setCount(num);
        // ...（后面不变）
    }
```

> `/addToCart`（加购）**保持原样**：它是"动作完成"型导航，跳成功页是对的；而且它在商品详情页（跨域），改 AJAX 要配 CORS。

---

## 三、前端改造（`cartList.html`）

**先删掉这些整页跳转的处理器**：`.itemCheck` 的 click、`.allCheck` 的 click、`.countOpsBtn` 的 click、`.countOpsNum` 的 blur、`li:nth-child(6) p:first-child`（单个删除）、`li:nth-child(2)`（批量删除）。

**换成下面这段**：

```html
<script type="text/javascript">
    /* ==================== 购物车页所有修改都走 AJAX ====================
       同域（都在 cart.gulimall.vip），不用配 CORS。
       服务端每个接口都返回整辆购物车，这里只刷新"会变的地方"，页面不跳。 */

    // 发请求 → 成功就用返回的购物车刷新数字；失败整页刷一次，回到服务端真实状态
    function submitCartChange(url, data) {
        $.post(url, data, function (res) {
            if (res.code === 0) {
                refreshCartNumbers(res.cart);
            } else {
                alert(res.msg);
                location.reload();
            }
        }).fail(function () {
            alert("网络异常，请稍后重试");
            location.reload();
        });
    }

    // 刷新：每行的小计/数量 + 底部四个数字（含两个金额）
    function refreshCartNumbers(cart) {
        if (!cart) { return; }
        var map = {};
        $.each(cart.items || [], function (i, item) { map[item.skuId] = item; });

        $(".One_ShopCon ul li").each(function () {
            var row = $(this);
            var item = map[row.find(".itemCheck").attr("skuId")];
            if (!item) { return; }
            row.find(".zj").html("￥" + Number(item.totalPrice).toFixed(2));
            row.find(".countOpsNum").val(item.count);   // 服务端钳过的数量回填
        });

        $(".foot_amount .fnt").eq(0).html("￥" + Number(cart.totalAmount).toFixed(2));
        $(".foot_amount .fnt").eq(1).html("￥" + Number(cart.reduce).toFixed(2));
        $(".foot_info .foot_val").eq(0).html(cart.checkedCountNum + " 件");
        $(".foot_info .foot_val").eq(1).html(cart.checkedCountType + " 种");
    }

    // 行的选中底色（勾上浅色）—— 顺便把之前那段死代码（.check）的效果补上
    function paintRow(check) {
        check.parents("li").last().css("background", check.prop("checked") ? "#fff4e8" : "none");
    }

    // 勾选 / 取消勾选：先本地改（乐观更新，点下去立刻有反馈），再发请求
    $(".itemCheck").click(function () {
        var box = $(this);
        paintRow(box);
        submitCartChange(box.attr("cartUrl") + "/checkItem",
            {skuId: box.attr("skuId"), checked: box.prop("checked") ? 1 : 0});
    });

    // 全选 / 取消全选
    $(".allCheck").click(function () {
        var checked = $(this).prop("checked");
        $(".itemCheck").prop("checked", checked).each(function () { paintRow($(this)); });
        submitCartChange($(this).attr("cartUrl") + "/checkAll", {checked: checked ? 1 : 0});
    });

    // 进页面时：按服务端渲染出来的勾选状态补底色 + 初始化全选框
    $(".itemCheck").each(function () { paintRow($(this)); });
    $(".allCheck").prop("checked",
        $(".itemCheck").length > 0 && $(".itemCheck:not(:checked)").length === 0);

    // 数量：-/+/手输 统一成"最终数量"提交，连点用 300ms 防抖（只发最后一次）
    var countTimer = null;
    function submitCount(ol, num) {
        clearTimeout(countTimer);
        countTimer = setTimeout(function () {
            var check = ol.find(".itemCheck");
            submitCartChange(check.attr("cartUrl") + "/countItem",
                {skuId: check.attr("skuId"), num: num});
        }, 300);
    }

    $(".countOpsBtn").click(function () {
        var ol = $(this).closest("ol");
        var input = ol.find(".countOpsNum");
        var tip = ol.find(".stockTip");
        var cur = parseInt(input.val(), 10);
        var stock = parseInt(ol.find(".itemCheck").attr("stock"), 10);
        var next;

        if ($(this).text() === "+") {
            next = cur + 1;
            if (!isNaN(stock) && next > stock) {
                tip.show();                 // 超出库存数量
                return false;
            }
        } else {
            if (cur <= 1) { return false; } // 最少 1 件
            next = cur - 1;
        }
        tip.hide();
        input.val(next);                    // 先本地改
        submitCount(ol, next);
    });

    $(".countOpsNum").each(function () {
        var input = $(this);
        var last = input.val();
        input.blur(function () {
            var ol = input.closest("ol");
            var tip = ol.find(".stockTip");
            var stock = parseInt(ol.find(".itemCheck").attr("stock"), 10);
            var val = $.trim(input.val());
            if (!/^\d+$/.test(val) || parseInt(val, 10) < 1) {
                input.val(last);            // 非法：退回上一次
                return;
            }
            if (!isNaN(stock) && parseInt(val, 10) > stock) {
                input.val(last);
                tip.show();
                return;
            }
            if (val === last) { return; }    // 没改就不发
            last = val;
            submitCount(ol, val);
        });
    });

    // 删除一件：成功后只把这行去掉，页面不跳
    $(".One_ShopCon ul li>div:nth-child(2) ol>li:nth-child(6) p:first-child").click(function () {
        var check = $(this).closest("ol").find(".itemCheck");
        $.post(check.attr("cartUrl") + "/deleteItem", {skuId: check.attr("skuId")}, function (res) {
            if (res.code !== 0) { alert(res.msg); return; }
            check.parents("li").last().remove();
            refreshCartNumbers(res.cart);
            if ($(".itemCheck").length === 0) { location.reload(); }  // 删空了就刷新，显示"无商品"
        });
    });

    // 批量删除：一次提交所有勾选的 skuId，成功后刷新（批量场景不差这一次局部刷新）
    $(".One_ShopFootBuy>div:first-child ul li:nth-child(2)").click(function () {
        var skuIds = [], cartUrl = "";
        $(".itemCheck:checked").each(function () {
            cartUrl = $(this).attr("cartUrl");
            skuIds.push($(this).attr("skuId"));
        });
        if (skuIds.length === 0) {
            $(".One_mb").show(); $(".One_moveGzIfNull").show();
            return false;
        }
        if (!confirm("确定删除选中的 " + skuIds.length + " 件商品？")) { return false; }
        $.post(cartUrl + "/deleteCheckedItems", {skuIds: skuIds.join(",")}, function () {
            location.reload();
        });
    });
</script>
```

---

## 四、为什么这么写（四个设计点）

### 1. 为什么用 POST 而不是 GET
GET 在 HTTP 语义上是**安全的**（不改变服务端状态）—— 浏览器会缓存它、会预取它、代理会缓存它。
用 GET 做写操作，可能被"缓存命中"导致写不进去，也可能被预取导致凭空多写一次。

### 2. 为什么叫"幂等"
| 写法 | 连点 3 次的结果 | 刷新重发的结果 |
|---|---|---|
| `数量 +1`（非幂等） | 变成 +3 件 ❌ | 又 +1 ❌ |
| `数量 = 3`（幂等） | 还是 3 件 ✅ | 还是 3 件 ✅ |

所以接口传的是**最终值**。有了幂等，前端只要保证"最后一次请求带着最终数量"就行 —— 于是：

### 3. 防抖（debounce）为什么是 300ms
用户连点 `+` 五次时，前端本地数字立刻 +5，请求却只在**最后一次点击后 300ms** 发一次，带的是最终值。
否则五连点 = 五次请求，每次都"设置为 N"，最终结果虽然对，但白白多打了四次 Redis。

### 4. 乐观更新 + 失败回滚
"点下去立刻变"比"转圈等服务器"体验好。所以：
- 勾选框、数量、行的底色都是**先本地改**；
- 请求失败（`code != 0` 或网络错误）→ **`location.reload()` 整页刷一次**，回到服务端的真实状态。
  这是最省的"回滚"实现：不用在前端维护一套撤销逻辑。

> 这也是为什么"提示用户等待"不如"乐观更新"—— 大部分请求都会成功，没必要让用户为小概率失败买单。

---

## 五、实施步骤与验证清单

1. 后端：改 `CartController` + `CartServiceImpl` 的小兜底 → 重启 cart 服务；
2. 前端：替换 `cartList.html` 的那段脚本 → `Ctrl + F5`；
3. 逐条验证：

- [ ] 勾选 / 取消勾选：**页面不跳**，勾选框和行底色立刻变，底部「已选数量 / 已选种类」跟着变；
- [ ] 全选 / 取消全选：所有行一起变，底部数字跟着变；
- [ ] 连点 `+` 五次：数字到 +5，Redis 里只有**一次** HSET（可以看 `redis-cli monitor`）；
- [ ] 数量手输：`abc` / `0` / 超过库存 都会退回上一次的值，超库存还会显示提示；合法值失焦后**页面不跳**，小计和总价立刻变；
- [ ] 删除一件：这一行消失，总价/件数跟着变；删到空时页面刷新并显示"购物车中无商品"；
- [ ] 批量删除：没勾选时弹"请至少选中一件商品"，勾了先 confirm，确认后列表刷新；
- [ ] 断网/停掉 cart 服务再点一次：应提示"网络异常"并整页刷新，而不是白屏。

---

## 六、压测建议（JMeter）

### 6.1 请求怎么填

| 字段 | 填什么 | 说明 |
|---|---|---|
| 协议 / 端口 | `http` / `13000` | **直接压服务端口**，绕开 Nginx 和网关，最接近"测服务本身" |
| 服务器名称或IP | `cart.gulimall.vip` | ⚠️ **只写主机名**，不能像截图里那样带 `/cart.html`（那是路径） |
| 路径 | `/countItem` | 写操作；读操作压 `/cart.html`（它最重，会渲染整页） |
| 方法 | `GET` → **改成 `POST`**（改造后） | 改造前还是 GET |
| 参数 | `skuId=10` / `num=1` | 勾上「包含等于」 |

> **最容易漏的一步：加 `HTTP Cookie 管理器`**（配置元件 → HTTP Cookie 管理器），里面加一条
> `user-key = <一个 UUID>`。购物车是**按 cookie 认人**的（游客用 `user-key`，登录用户用会话 cookie）——
> 不带 cookie 时每次请求都会被当成一个**新游客**，等于在测"几十万个空购物车的创建"，既不是真实场景，还会把 Redis 内存打满。
>
> 想更真实就用 `CSV Data Set Config` 准备 N 个不同的 `user-key`（每个线程一个用户），模拟多用户并发。

### 6.2 线程组怎么配

- 冒烟：1 线程 × 1 循环（先用「察看结果树」确认返回是 `{"code":0,...}`）；
- 压测：100 线程 / Ramp-up 10s / 循环 50 或「永远 + 持续时间 300s」；
- 断言：JSON 断言 `$.code == 0`（否则 4xx/5xx 会被当成成功）；
- 报告：聚合报告看 **TPS / p90 / 错误率**。

### 6.3 怎么才能"逼出"Redis 超时与重试

单纯的并发量基本压不出 Redis 超时（Redis 单机十万 QPS 级）。要复现，用这两招主动制造阻塞：

```cmd
redis-cli -a <密码> CLIENT PAUSE 5000    # 让所有客户端阻塞 5 秒
redis-cli -a <密码> DEBUG SLEEP 5        # 占住一个连接 5 秒（单机 debug 命令）
```

期间压测，就能看到应用侧的 Redis 命令超时、连接是否复用、有没有重试和降级。
（`timeout` 建议配 `spring.data.redis.timeout: 2s` 快速失败，别挂默认的 60 秒。）

### 6.4 压测时重点看这几处

| 看什么 | 在哪看 | 说明 |
|---|---|---|
| Redis 有没有超时 | 应用日志里的 `RedisCommandTimeoutException` | 有 ⇒ 命令超时/连接被阻塞 |
| 是不是连接不够 | `redis-cli info clients` 的 `connected_clients` | Lettuce 默认多路复用，不该随并发线性涨 |
| 有没有慢命令 | `redis-cli slowlog get 10` | 出现 `KEYS`/大 `HGETALL` 就要改 |
| 真正的瓶颈 | cart 日志里的 feign 调用 | ⚠️ 见下 |

> ⚠️ **这个压测大概率先压垮的是 ware，不是 Redis**：现在 `getCartItems` 里是**每个商品一次 feign 查库存**，
> 一次 `/cart.html` 渲染 = N 次远程调用。压到几百并发时，ware 会先被打挂。
> 所以压测前建议先把库存查询改回**批量接口**（`hasStock(List<Long>)` 那种），一次查完。

---

## 七、Redis 持久化（已改 `deploy/redis/conf/redis.conf`）

**改动**（`docker compose restart redis` 生效）：

| 配置 | 原来 | 现在 | 为什么 |
|---|---|---|---|
| `maxmemory-policy` | `allkeys-lru` | **`noeviction`** | `allkeys-lru` 内存一满会**静默删掉任何 key**，包括购物车和登录会话 —— 这就是"购物车莫名清空、莫名其妙要重新登录"的元凶 |
| `maxmemory` | `1024mb` | `2048mb` | 这个实例同时住着购物车 + 会话 + 商品缓存，1G 太小（按机器内存调） |
| `dir` | 无 | `/data` | 显式指向 compose 挂进来的数据卷，容器重建也不丢 |
| `save` | 无 | `900 1` / `300 10` | AOF 之外保留 RDB，用于备份和 AOF 损坏时兜底 |
| `appendonly` / `appendfsync` | `yes` / `everysec` | 不变 ✅ | 原本就是对的：最多丢 1 秒 |

**验证**：

```cmd
docker compose restart redis
redis-cli -a <密码> config get maxmemory-policy        :: 期望 noeviction
redis-cli -a <密码> info persistence | findstr /I "aof_enabled rdb_last_save_time"
```

再加购几件 → 重启 Redis 容器 → 回购物车看还在不在。在 = 持久化生效。

**两个提醒**：① 以后别用 `FLUSHALL`（AOF 里也会记上，等于清空所有人购物车）；② 更正规的做法是**拆实例**：购物车 + 会话一个 `noeviction` 实例，纯缓存另一个用 `allkeys-lru`（缓存被淘汰无所谓）。

---

## 八、后续可选项（这次不做）

1. **库存查询改批量**（性价比最高）：`getCartItems` 目前是逐件 feign，改成 `hasStock(List<Long>)` 一次查完；
2. **`checkAll` 用 pipeline**：现在是 N 次 `HSET`，可以用 `executePipelined` 或一次 `HMSET` 合并；
3. **登录用户购物车落 MySQL**（游客车只放 Redis，丢了无所谓）：`cart_item(user_id, sku_id, count, checked)` + `unique(user_id, sku_id)`，异步 upsert；
4. **降级**：Redis 挂了让购物车转只读并给出友好提示，别整页 500。
