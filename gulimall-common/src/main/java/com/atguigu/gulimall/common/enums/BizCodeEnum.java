package com.atguigu.gulimall.common.enums;

/**
 * 错误码和错误信息定义类<br>
 * 1. 错误码固定 5 位数字，前 1~2 位表示模块，其余位数表示模块内序号
 * 2. 模块段 = 该服务的 server.port ÷ 1000。例如 13000 → 13，8000 → 8，9000 → 9
 * 3. 拿到错误码就能直接定位到该查哪个服务的日志，模块端口对照表：<br>
 * <pre>
 *  07  gulimall-coupon          7000
 *  08  gulimall-member          8000
 *  09  gulimall-order           9000
 *  10  gulimall-product        10000
 *  11  gulimall-ware           11000
 *  12  gulimall-search         12000
 *  13  gulimall-cart           13000
 *  20  gulimall-auth-server    20000
 *  30  gulimall-third-party    30000
 *  88  gulimall-gateway           88
 *  99  通用 / 框架级（保留段）
 * </pre>
 * 4. 99 是保留段：gulimall-common、gulimall-api 只是库、没有端口，<br>
 * &nbsp&nbsp&nbsp&nbsp 框架级错误（系统未知异常、参数格式校验失败）都归到这里
 * 5. 跨模块复用的码按「业务域」归属，而不是按谁抛出来的：<br>
 * &nbsp&nbsp&nbsp&nbsp OAUTH_LOGIN_EXCEPTION 归认证域（third-party 换 token 失败也用它）<br>
 * &nbsp&nbsp&nbsp&nbsp PRODUCT_UP_EXCEPTION 归商品域（实际是 search 收到上架消息时抛出）<br>
 * 6. 业务模块序号从 001 开始，通用段序号从 000 开始
 *
 * <p>⚠️ 7/8/9 段的码只能写成 80001 这种形式，不能写成 08001 —— <br>
 * Java 里前导 0 是八进制字面量，8 不是合法的八进制位，08001 编译不过
 */
public enum BizCodeEnum {

    /* ==================== 99：通用 / 框架级 ==================== */
    UNKNOW_EXCEPTION(99000, "系统未知异常"),
    VALID_EXCEPTION(99001, "参数格式校验失败"),
    ILLEGAL_CHARACTER_EXCEPTION(99002, "包含非法字符"),

    /* ==================== 08：会员 gulimall-member 8000 ==================== */
    USER_NAME_EXISTS(8001, "用户名{0}已存在"),
    PHONE_EXISTS(8002, "手机号{0}已存在"),
    LOGIN_ACCT_PASSWORD_INVALID(8003, "账号或密码错误"),
    /** 会员端接口需要登录态，但会话里没有登录信息 */
    MEMBER_NOT_LOGIN(8004, "请先登录"),

    /* ==================== 09：订单 gulimall-order 9000 ==================== */
    /** 提交订单时，会话里没有登录用户 */
    ORDER_NOT_LOGIN(9001, "登录已失效，请重新登录"),
    /** 防重令牌不存在或不匹配（重复提交 / 页面停留太久令牌已过期） */
    ORDER_TOKEN_INVALID(9002, "订单已提交或已过期，请刷新页面重新提交"),
    /** 收货地址查不到，或者不属于当前登录用户 */
    ORDER_ADDRESS_INVALID(9003, "收货地址无效，请重新选择"),
    /** 购物车里没有任何商品 */
    ORDER_CART_EMPTY(9004, "购物车为空，无法下单"),
    /** 服务端重算的商品总额和前端提交的对不上（期间价格有变） */
    ORDER_PRICE_CHANGED(9005, "商品价格有变动，请刷新页面重新确认后再提交"),
    /** 下单前的库存校验没过，{0} 是缺货的 skuId 列表 */
    ORDER_STOCK_NOT_ENOUGH(9006, "以下商品库存不足，请调整后重试：{0}"),
    /** Redis 预扣库存时发现某个 sku 不够 */
    ORDER_STOCK_INSUFFICIENT(9007, "商品库存不足，请调整后重试"),
    /** 调 ware 锁定数据库库存失败 */
    ORDER_STOCK_DEDUCT_FAILED(9008, "库存锁定失败，请稍后重试"),
    /** 前端没把运费带上来（页面停留太久 / 状态过期） */
    ORDER_FREIGHT_MISSING(9009, "运费信息缺失，请刷新页面重新提交"),
    /** 前端没把应付金额带上来（页面停留太久 / 状态过期） */
    ORDER_PAYPRICE_MISSING(9010, "应付金额信息缺失，请刷新页面重新提交"),
    /** 前端没带选仓信息，或选仓信息和购物车商品对不上 */
    ORDER_WAREHOUSE_MISSING(9011, "发货仓库信息缺失，请刷新页面重新提交"),
    /** 提交订单时库存正在盘点（写锁被别人持有） */
    ORDER_STOCK_BUSY(9012, "库存正在盘点中，请稍后重试"),
    /** 提交订单线程被中断 */
    ORDER_SUBMIT_INTERRUPTED(9013, "下单被中断，请稍后重试"),
    /** 取消订单入参缺订单号 */
    ORDER_SN_REQUIRED(9014, "订单号不能为空"),
    /** 订单不存在，或者不属于当前登录用户 */
    ORDER_NOT_FOUND_OR_NOT_OWNER(9015, "订单不存在或无权操作"),
    /** 只有待付款订单允许取消 */
    ORDER_CANCEL_NOT_ALLOWED(9016, "当前订单状态不允许取消"),
    /** 只有待付款订单允许发起支付 */
    ORDER_PAY_NOT_ALLOWED(9017, "当前订单状态不允许支付"),
    /** 订单应付金额为空或不是正数 */
    ORDER_PAY_AMOUNT_INVALID(9018, "订单应付金额异常"),
    /** 调支付宝创建支付单失败 */
    ORDER_PAY_CREATE_FAILED(9019, "创建支付宝支付失败，请稍后重试"),
    /** 只有已发货订单允许确认收货 */
    ORDER_RECEIVE_NOT_ALLOWED(9020, "当前订单状态不允许确认收货"),
    /** 只有已收货/已完成的订单允许申请退货 */
    ORDER_RETURN_NOT_ALLOWED(9021, "当前订单状态不允许申请退货"),
    /** 同一订单的同一商品已有未完成或已完成的退货申请 */
    ORDER_RETURN_ALREADY_APPLIED(9022, "该商品已经提交过退货申请"),
    /** 退货数量不在 1 ~ 购买数量之间 */
    ORDER_RETURN_COUNT_INVALID(9023, "退货数量不正确"),
    /** 退货单不存在 */
    ORDER_RETURN_NOT_FOUND(9024, "退货单不存在"),
    /** 退货单已经处理完成，不能重复处理 */
    ORDER_RETURN_ALREADY_COMPLETED(9025, "退货单已处理完成，不能重复处理"),
    /** 退款失败（已写入退款记录，定时任务会继续重试） */
    ORDER_RETURN_REFUND_FAILED(9026, "退款失败，已记录待重试"),

    /* ==================== 10：商品 gulimall-product 10000 ==================== */
    SAME_RECORD_EXCEPTION(10001, "已存在相同的品牌和分类记录"),
    PRODUCT_UP_EXCEPTION(10002, "商品上架异常"),

    /* ==================== 11：仓储 gulimall-ware 11000 ==================== */
    /** 管理员改库存时抢写锁超时 */
    WARE_STOCK_BUSY(11001, "库存正在被操作，请稍后重试"),
    /** 管理员改库存时线程被中断 */
    WARE_STOCK_OPERATE_INTERRUPTED(11002, "库存操作被中断，请稍后重试"),
    /** 收货地址查不到 */
    WARE_ADDRESS_NOT_FOUND(11003, "收货地址不存在"),
    /** 调百度算运费失败 / 距离结果为空 */
    WARE_FARE_CALC_FAILED(11004, "运费计算失败，请稍后重试"),
    /** 锁库存入参缺订单号 */
    WARE_ORDER_SN_REQUIRED(11005, "订单号不能为空"),
    /** 锁库存入参没有商品 */
    WARE_STOCK_ITEMS_REQUIRED(11006, "锁库存的商品不能为空"),
    /** 同一订单重复锁库存 */
    WARE_ORDER_TASK_ALREADY_PROCESSED(11007, "订单{0}的库存工作单已处理，不能重复锁定"),
    /** 指定仓库可用库存不足 */
    WARE_STOCK_NOT_ENOUGH(11008, "库存不足，请刷新后重试"),
    /** 指定仓库没有这个 sku */
    WARE_CHOSEN_WAREHOUSE_EMPTY(11009, "选中的仓库没有该商品，请刷新后重试"),
    /** 没传选仓信息 */
    WARE_CHOSEN_WAREHOUSE_REQUIRED(11010, "缺少发货仓库信息，请刷新页面重新提交"),
    /** 解锁时发现 stock_locked 不够减 */
    WARE_STOCK_RELEASE_FAILED(11011, "解锁库存失败：工作单明细{0}的锁定数量不足"),
    /** 支付扣减时工作单不存在 */
    WARE_ORDER_TASK_NOT_FOUND(11012, "订单{0}的库存工作单不存在"),
    /** 支付扣减时工作单已经解锁 */
    WARE_ORDER_TASK_RELEASED(11013, "订单{0}的库存工作单已解锁，不能扣减"),
    /** 支付扣减时库存不足 */
    WARE_STOCK_DEDUCT_FAILED(11014, "扣减库存失败：工作单明细{0}库存不足"),
    /** 解锁时库存正在盘点 */
    WARE_STOCK_RELEASING(11015, "库存正在盘点，解锁稍后重试"),
    /** 解锁时线程被中断 */
    WARE_STOCK_RELEASE_INTERRUPTED(11016, "解锁库存被中断，稍后重试"),
    /** 退货回补时找不到已扣减的库存工作单明细 */
    WARE_ORDER_TASK_DETAIL_NOT_FOUND(11017, "订单{0}的库存扣减记录不存在，无法回补库存"),
    /** 退货回补数量不合法 */
    WARE_RETURN_STOCK_NUM_INVALID(11018, "退货回补数量不正确"),

    /* ==================== 13：购物车 gulimall-cart 13000 ==================== */
    /** 加购前远程查库存，无货不写购物车 */
    SKU_NO_STOCK_EXCEPTION(13001, "商品库存不足"),
    /** 同一用户并发操作同一个购物车，抢锁超时 */
    CART_BUSY_EXCEPTION(13002, "操作太频繁，请稍后重试"),

    /* ==================== 14：秒杀 gulimall-seckill 14000 ==================== */
    /** 秒杀接口需要登录态 */
    SECKILL_NOT_LOGIN(14001, "请先登录后再抢购"),
    /** 还没到开始时间 */
    SECKILL_NOT_STARTED(14002, "秒杀活动还没开始"),
    /** 已过结束时间或场次已停止 */
    SECKILL_ENDED(14003, "秒杀活动已结束"),
    /** Redis 库存不够 */
    SECKILL_STOCK_INSUFFICIENT(14004, "手慢了，商品已抢光"),
    /** 超过每人限购数量 */
    SECKILL_LIMIT_EXCEEDED(14005, "超过每人限购数量"),
    /** 10 秒内的重复请求 */
    SECKILL_REQUEST_DUPLICATE(14006, "请求处理中，请勿重复提交"),
    /** 随机码和上架时写进 Redis 的对不上 */
    SECKILL_RANDOM_CODE_INVALID(14007, "秒杀随机码无效，请刷新页面"),
    /** 秒杀订单不存在或不属于当前会员 */
    SECKILL_ORDER_NOT_FOUND(14008, "秒杀订单不存在或无权操作"),
    /** 场次、商品、数量、地址等参数不合法 */
    SECKILL_PARAM_INVALID(14009, "秒杀参数不合法"),
    /** 释放秒杀库存时状态冲突 */
    SECKILL_RELEASE_CONFLICT(14010, "秒杀库存释放冲突，请稍后重试"),
    /** 场次不存在、未启用或商品不在场次里 */
    SECKILL_SESSION_INVALID(14011, "秒杀场次不存在或已停用"),
    /** 建单消息投递或建单失败 */
    SECKILL_ORDER_BUILD_FAILED(14012, "秒杀订单创建失败，库存已退回"),
    /** Sentinel 限流/熔断 */
    SECKILL_FLOW_LIMIT(14013, "当前抢购人数过多，请稍后再试"),

    /* ==================== 20：认证 / 短信 gulimall-auth-server 20000 ==================== */
    /** 同一个手机号 60 秒内重复请求验证码 */
    SMS_CODE_EXCEPTION(20001, "验证码获取频率太高，请稍后再试"),
    /** 调用第三方短信服务失败 */
    SMS_SEND_EXCEPTION(20002, "短信发送失败，请稍后重试"),
    /** 社交登录失败（换取 token 失败 / 第三方接口异常 / 用户信息不完整） */
    OAUTH_LOGIN_EXCEPTION(20003, "社交登录失败，请稍后重试"),

    /* ==================== 30：第三方 gulimall-third-party 30000 ==================== */
    /** 百度地图距离计算失败（地址解析不出来 / AK 权限不足 / 配额超限等） */
    MAP_DISTANCE_EXCEPTION(30001, "距离计算失败，请稍后重试");

    private final int code;
    private final String msg;

    BizCodeEnum(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public int getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

}
